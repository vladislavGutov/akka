/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import java.util.concurrent.ConcurrentHashMap

import scala.collection.immutable
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import akka.actor.DynamicAccess
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.setup.Setup
import akka.annotation.InternalStableApi
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.util.unused
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import com.typesafe.config.Config

object JacksonObjectMapperProvider extends ExtensionId[JacksonObjectMapperProvider] with ExtensionIdProvider {
  override def get(system: ActorSystem): JacksonObjectMapperProvider = super.get(system)

  override def lookup = JacksonObjectMapperProvider

  override def createExtension(system: ExtendedActorSystem): JacksonObjectMapperProvider =
    new JacksonObjectMapperProvider(system)

  /**
   * INTERNAL API: Use [[JacksonObjectMapperProvider#create]]
   *
   * This is needed by one test in Lagom where the ObjectMapper is created without starting and ActorSystem.
   */
  @InternalStableApi
  def createObjectMapper(
      serializerIdentifier: Int,
      jsonFactory: Option[JsonFactory],
      objectMapperFactory: JacksonObjectMapperFactory,
      config: Config,
      dynamicAccess: DynamicAccess,
      log: Option[LoggingAdapter]) = {
    import scala.collection.JavaConverters._

    val mapper = objectMapperFactory.newObjectMapper(serializerIdentifier, jsonFactory)

    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)

    val configuredSerializationFeatures =
      features(config, "akka.serialization.jackson.serialization-features").map {
        case (enumName, value) => SerializationFeature.valueOf(enumName) -> value
      }
    val serializationFeatures =
      objectMapperFactory.overrideConfiguredSerializationFeatures(serializerIdentifier, configuredSerializationFeatures)
    serializationFeatures.foreach {
      case (feature, value) => mapper.configure(feature, value)
    }

    val configuredDeserializationFeatures =
      features(config, "akka.serialization.jackson.deserialization-features").map {
        case (enumName, value) => DeserializationFeature.valueOf(enumName) -> value
      }
    val deserializationFeatures =
      objectMapperFactory.overrideConfiguredDeserializationFeatures(
        serializerIdentifier,
        configuredDeserializationFeatures)
    deserializationFeatures.foreach {
      case (feature, value) => mapper.configure(feature, value)
    }

    val configuredModules = config.getStringList("akka.serialization.jackson.jackson-modules").asScala
    val modules1 =
      if (configuredModules.contains("*"))
        ObjectMapper.findModules(dynamicAccess.classLoader).asScala
      else
        configuredModules.flatMap { fqcn ⇒
          dynamicAccess.createInstanceFor[Module](fqcn, Nil) match {
            case Success(m) ⇒ Some(m)
            case Failure(e) ⇒
              log.foreach(
                _.error(
                  e,
                  s"Could not load configured Jackson module [$fqcn], " +
                  "please verify classpath dependencies or amend the configuration " +
                  "[akka.serialization.jackson-modules]. Continuing without this module."))
              None
          }
        }

    val modules2 = modules1.map { module ⇒
      if (module.isInstanceOf[ParameterNamesModule])
        // ParameterNamesModule needs a special case for the constructor to ensure that single-parameter
        // constructors are handled the same way as constructors with multiple parameters.
        // See https://github.com/FasterXML/jackson-module-parameter-names#delegating-creator
        new ParameterNamesModule(JsonCreator.Mode.PROPERTIES)
      else module
    }.toList

    val modules3 = objectMapperFactory.overrideConfiguredModules(serializerIdentifier, modules2)

    modules3.foreach { module =>
      mapper.registerModule(module)
      log.foreach(_.debug("Registered Jackson module [{}]", module.getClass.getName))
    }

    mapper
  }

  private def features(config: Config, section: String): immutable.Seq[(String, Boolean)] = {
    import scala.collection.JavaConverters._
    val cfg = config.getConfig(section)
    cfg.root.keySet().asScala.map(key => key -> cfg.getBoolean(key)).toList
  }
}

// FIXME docs
final class JacksonObjectMapperProvider(system: ExtendedActorSystem) extends Extension {
  private val objectMappers = new ConcurrentHashMap[Int, ObjectMapper]

  /**
   * Returns an existing Jackson `ObjectMapper` that was created previously with this method, or
   * creates a new instance.
   *
   * The `ObjectMapper` is created with sensible defaults and modules configured
   * in `akka.serialization.jackson.jackson-modules`. It's using [[JacksonObjectMapperProviderSetup]]
   * if the `ActorSystem` is started with such [[akka.actor.setup.ActorSystemSetup]].
   *
   * The returned `ObjecctMapper` must not be modified, because it may already be in use and such
   * modifications are not thread-safe.
   *
   * @param serializerIdentifier the identifier of the serializer that is using this `ObjectMapper`,
   *                             there will be one `ObjectInstance` per serializer
   * @param jsonFactory optional `JsonFactory` such as `SmileFactory`, for plain JSON `None` (defaults)
   *                    can be used
   */
  def getOrCreate(serializerIdentifier: Int, jsonFactory: Option[JsonFactory]): ObjectMapper = {
    objectMappers.computeIfAbsent(serializerIdentifier, _ => create(serializerIdentifier, jsonFactory))
  }

  // FIXME Java API, Optional vs Option

  /**
   * Creates a new instance of a Jackson `ObjectMapper` with sensible defaults and modules configured
   * in `akka.serialization.jackson.jackson-modules`. It's using [[JacksonObjectMapperProviderSetup]]
   * if the `ActorSystem` is started with such [[akka.actor.setup.ActorSystemSetup]].
   *
   * @param serializerIdentifier the identifier of the serializer that is using this `ObjectMapper`,
   *                             there will be one `ObjectInstance` per serializer
   * @param jsonFactory optional `JsonFactory` such as `SmileFactory`, for plain JSON `None` (defaults)
   *                    can be used
   * @see [[JacksonObjectMapperProvider#getOrCreate]]
   */
  def create(serializerIdentifier: Int, jsonFactory: Option[JsonFactory]): ObjectMapper = {
    val log = Logging.getLogger(system, JacksonObjectMapperProvider.getClass)
    val config = system.settings.config
    val dynamicAccess = system.dynamicAccess

    val factory = system.settings.setup.get[JacksonObjectMapperProviderSetup] match {
      case Some(setup) => setup.factory
      case None        => new JacksonObjectMapperFactory // default
    }

    JacksonObjectMapperProvider.createObjectMapper(
      serializerIdentifier,
      jsonFactory,
      factory,
      config,
      dynamicAccess,
      Some(log))
  }

}

object JacksonObjectMapperProviderSetup {

  /**
   * Scala API: factory for defining a `JacksonObjectMapperProvider` that is passed in when ActorSystem
   * is created rather than creating one from configured class name.
   */
  def apply(factory: JacksonObjectMapperFactory): JacksonObjectMapperProviderSetup =
    new JacksonObjectMapperProviderSetup(factory)

  /**
   * Java API: factory for defining a `JacksonObjectMapperProvider` that is passed in when ActorSystem
   * is created rather than creating one from configured class name.
   */
  def create(factory: JacksonObjectMapperFactory): JacksonObjectMapperProviderSetup =
    apply(factory)

}

/**
 * Setup for defining a `JacksonObjectMapperProvider` that can be passed in when ActorSystem
 * is created rather than creating one from configured class name. Create a subclass of
 * [[JacksonObjectMapperFactory]] and override the methods to amend the defaults.
 */
final class JacksonObjectMapperProviderSetup(val factory: JacksonObjectMapperFactory) extends Setup

/**
 * Used with [[JacksonObjectMapperProviderSetup]] for defining a `JacksonObjectMapperProvider` that can be
 * passed in when ActorSystem is created rather than creating one from configured class name.
 * Create a subclass and override the methods to amend the defaults.
 */
class JacksonObjectMapperFactory {

  /**
   * Override this method to create a new custom instance of `ObjectMapper` for the given `serializerIdentifier`.
   *
   * @param serializerIdentifier the identifier of the serializer that is using this `ObjectMapper`,
   *                             there will be one `ObjectInstance` per serializer
   * @param jsonFactory optional `JsonFactory` such as `SmileFactory`, for plain JSON `None` (defaults)
   *                    can be used
   */
  def newObjectMapper(@unused serializerIdentifier: Int, jsonFactory: Option[JsonFactory]): ObjectMapper =
    new ObjectMapper(jsonFactory.orNull)

  // FIXME Java API

  /**
   * After construction of the `ObjectMapper` the configured serialization features are applied to
   * the mapper. These features can be amended programatically by overriding this method and
   * return the features that are to be applied to the `ObjectMapper`.
   *
   * @param serializerIdentifier the identifier of the serializer that is using this `ObjectMapper`,
   *                             there will be one `ObjectInstance` per serializer
   * @param configuredFeatures the list of `SerializationFeature` that were configured in
   *                           `akka.serialization.jackson.serialization-features`
   */
  def overrideConfiguredSerializationFeatures(
      @unused serializerIdentifier: Int,
      configuredFeatures: immutable.Seq[(SerializationFeature, Boolean)])
      : immutable.Seq[(SerializationFeature, Boolean)] =
    configuredFeatures

  /**
   * After construction of the `ObjectMapper` the configured deserialization features are applied to
   * the mapper. These features can be amended programatically by overriding this method and
   * return the features that are to be applied to the `ObjectMapper`.
   *
   * @param serializerIdentifier the identifier of the serializer that is using this `ObjectMapper`,
   *                             there will be one `ObjectInstance` per serializer
   * @param configuredFeatures the list of `DeserializationFeature` that were configured in
   *                           `akka.serialization.jackson.deserialization-features`
   */
  def overrideConfiguredDeserializationFeatures(
      @unused serializerIdentifier: Int,
      configuredFeatures: immutable.Seq[(DeserializationFeature, Boolean)])
      : immutable.Seq[(DeserializationFeature, Boolean)] =
    configuredFeatures

  /**
   * After construction of the `ObjectMapper` the configured modules are added to
   * the mapper. These modules can be amended programatically by overriding this method and
   * return the modules that are to be applied to the `ObjectMapper`.
   *
   * @param serializerIdentifier the identifier of the serializer that is using this `ObjectMapper`,
   *                             there will be one `ObjectInstance` per serializer
   * @param configuredModules the list of `Modules` that were configured in
   *                           `akka.serialization.jackson.deserialization-features`
   */
  def overrideConfiguredModules(
      @unused serializerIdentifier: Int,
      configuredModules: immutable.Seq[Module]): immutable.Seq[Module] =
    configuredModules

}
