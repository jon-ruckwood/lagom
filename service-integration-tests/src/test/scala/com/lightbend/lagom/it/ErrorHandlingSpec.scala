/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package com.lightbend.lagom.it

import java.util
import java.util.concurrent.{ CompletableFuture, CompletionStage, ExecutionException, TimeUnit }
import javax.inject.Singleton
import javax.inject.{ Inject, Provider }
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.javadsl.{ Source => JSource }
import akka.util.ByteString
import com.lightbend.lagom.internal.api._
import com.lightbend.lagom.internal.client.ServiceClientImplementor
import com.lightbend.lagom.internal.server._
import com.lightbend.lagom.it.mocks._
import com.lightbend.lagom.javadsl.api.Descriptor.{ NamedCallId, RestCallId, CallId, Call }
import com.lightbend.lagom.javadsl.api.deser.MessageSerializer.{ NegotiatedDeserializer, NegotiatedSerializer }
import com.lightbend.lagom.javadsl.api.deser.{ StreamedMessageSerializer, DeserializationException, SerializationException, StrictMessageSerializer }
import com.lightbend.lagom.javadsl.api.transport._
import com.lightbend.lagom.javadsl.api._
import com.lightbend.lagom.javadsl.jackson.{ JacksonExceptionSerializer, JacksonSerializerFactory }
import org.pcollections.TreePVector
import play.api.libs.streams.AkkaStreams
import play.api.{ Application, Environment }
import play.api.inject._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import com.typesafe.config.ConfigFactory
import akka.actor.ReflectiveDynamicAccess
import com.lightbend.lagom.internal.jackson.JacksonObjectMapperProvider

/**
 * A brief explanation of this spec.
 *
 * It checks that error handling works in all combinations of strict/streamed request/responses.
 *
 * In order to inject errors, we create and resolve the service descriptor, and then replace specific parts, for
 * example, the request or response serializer on either the server or the client, or the service call, with
 * components that throw the errors that we want to test the handling for.  We then have a suite of tests (in the
 * test method) that defines all these errors and tests.  The actual making of the call though is abstracted away,
 * this suite of tests is then executed once for each combination of strict/streamed request/responses, which tells
 * the test suite which endpoint in the descriptor to modify, and how to make a call to that endpoint.
 */
class ErrorHandlingSpec extends ServiceSupport {

  "Service error handling" when {
    "handling errors with plain HTTP calls" should {
      tests(new RestCallId(Method.POST, "/:part1/:part2")) { implicit app => client =>
        val result = client.mockCall().invoke(new MockId("a", 1), new MockRequestEntity("b", 2))
        try {
          result.toCompletableFuture.get(10, TimeUnit.SECONDS)
          throw sys.error("Did not fail")
        } catch {
          case e: ExecutionException => e.getCause
        }
      }
    }

    "handling errors with streamed response calls" should {
      tests(new NamedCallId("streamResponse")) { implicit app => client =>
        val result = client.streamResponse().invoke(new MockRequestEntity("b", 2))
        try {
          val resultSource = result.toCompletableFuture.get(10, TimeUnit.SECONDS)
          Await.result(resultSource.asScala.runWith(Sink.ignore), 10.seconds)
          throw sys.error("No error was thrown")
        } catch {
          case e: ExecutionException => e.getCause
          case NonFatal(other)       => other
        }
      }
    }

    "handling errors with streamed request calls" should {
      tests(new NamedCallId("streamRequest")) { implicit app => client =>
        val result = client.streamRequest()
          .invoke(Source.single(new MockRequestEntity("b", 2)).concat(Source.maybe).asJava)
        try {
          result.toCompletableFuture.get(10, TimeUnit.SECONDS)
          throw sys.error("No error was thrown")
        } catch {
          case e: ExecutionException => e.getCause
          case NonFatal(other)       => other
        }
      }
    }

    "handling errors with bidirectional streamed calls" should {
      tests(new NamedCallId("bidiStream")) { implicit app => client =>
        val result = client.bidiStream()
          .invoke(Source.single(new MockRequestEntity("b", 2)).concat(Source.maybe).asJava)
        try {
          val resultSource = result.toCompletableFuture.get(10, TimeUnit.SECONDS)
          Await.result(resultSource.asScala.runWith(Sink.ignore), 10.seconds)
          throw sys.error("No error was thrown")
        } catch {
          case e: ExecutionException => e.getCause
          case NonFatal(other)       => other
        }
      }
    }
  }

  def tests(callId: CallId)(makeCall: Application => MockService => Throwable) = {
    "handle errors in request serialization" in withClient(changeClient = change(callId)(failingRequestSerializer)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: SerializationException =>
          e.errorCode should ===(TransportErrorCode.InternalServerError)
          e.exceptionMessage.detail should ===("failed serialize")
      }
    }
    "handle errors in request deserialization negotiation" in withClient(changeServer = change(callId)(failingRequestNegotiation)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: UnsupportedMediaType =>
          e.errorCode should ===(TransportErrorCode.UnsupportedMediaType)
          e.exceptionMessage.detail should include("application/json")
          e.exceptionMessage.detail should include("unsupported")
      }
    }
    "handle errors in request deserialization" in withClient(changeServer = change(callId)(failingRequestSerializer)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: DeserializationException =>
          e.errorCode should ===(TransportErrorCode.UnsupportedData)
          e.exceptionMessage.detail should ===("failed deserialize")
      }
    }
    "handle errors in service call invocation" in withClient(changeServer = change(callId)(failingServiceCall)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: TransportException =>
          // By default, we don't give out internal details of exceptions, for security reasons
          e.exceptionMessage.name should ===("Exception")
          e.exceptionMessage.detail should ===("")
          e.errorCode should ===(TransportErrorCode.InternalServerError)
      }
    }
    "handle asynchronous errors in service call invocation" in withClient(changeServer = change(callId)(asyncFailingServiceCall)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: TransportException =>
          e.exceptionMessage.name should ===("Exception")
          e.exceptionMessage.detail should ===("")
          e.errorCode should ===(TransportErrorCode.InternalServerError)
      }
    }
    "handle stream errors in service call invocation" in withClient(changeServer = change(callId)(failingStreamedServiceCall)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: TransportException =>
          e.exceptionMessage.name should ===("Exception")
          e.exceptionMessage.detail should ===("")
          e.errorCode should ===(TransportErrorCode.InternalServerError)
      }
    }
    "handle errors in response serialization negotiation" in withClient(changeServer = change(callId)(failingResponseNegotation)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: NotAcceptable =>
          e.errorCode should ===(TransportErrorCode.NotAcceptable)
          e.exceptionMessage.detail should include("application/json")
          e.exceptionMessage.detail should include("not accepted")
      }
    }
    "handle errors in response serialization" in withClient(changeServer = change(callId)(failingResponseSerializer)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: SerializationException =>
          e.errorCode should ===(TransportErrorCode.InternalServerError)
          e.exceptionMessage.detail should ===("failed serialize")
      }
    }
    "handle errors in response deserialization negotiation" in withClient(changeClient = change(callId)(failingResponseNegotation)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: UnsupportedMediaType =>
          e.errorCode should ===(TransportErrorCode.UnsupportedMediaType)
          e.exceptionMessage.detail should include("unsupported")
          try {
            e.exceptionMessage.detail should include("application/json")
          } catch {
            case e => println("SKIPPED - Requires https://github.com/playframework/playframework/issues/5322")
          }
      }
    }
    "handle errors in response deserialization" in withClient(changeClient = change(callId)(failingResponseSerializer)) { implicit app => client =>
      makeCall(app)(client) match {
        case e: DeserializationException =>
          e.errorCode should ===(TransportErrorCode.UnsupportedData)
          e.exceptionMessage.detail should ===("failed deserialize")
      }
    }
  }

  /**
   * This sets up the server and the client, but allows them to be modified before actually creating them.
   */
  def withClient(changeClient: Descriptor => Descriptor = identity, changeServer: Descriptor => Descriptor = identity)(block: Application => MockService => Unit): Unit = {

    val environment = Environment.simple()
    val jacksonSerializerFactory = new JacksonSerializerFactory(new JacksonObjectMapperProvider(
      ConfigFactory.load(), new ReflectiveDynamicAccess(environment.classLoader), None
    ))
    val jacksonExceptionSerializer = new JacksonExceptionSerializer
    val descriptor = ServiceReader.readServiceDescriptor(environment.classLoader, classOf[MockService])
    val resolved = ServiceReader.resolveServiceDescriptor(descriptor, environment.classLoader,
      Map(JacksonPlaceholderSerializerFactory -> jacksonSerializerFactory),
      Map(JacksonPlaceholderExceptionSerializer -> jacksonExceptionSerializer))

    withServer(
      _.bindings(
        bind[ServiceInfo].to(new ServiceInfoProvider(classOf[MockService]))
      )
        .overrides(bind[ResolvedServices].to(new MockResolvedServicesProvider(resolved, changeServer)))
    ) { app =>
        val clientImplementor = app.injector.instanceOf[ServiceClientImplementor]
        val clientDescriptor = changeClient(resolved)
        val client = clientImplementor.implement(classOf[MockService], clientDescriptor)
        block(app)(client)
      }
  }

  @Singleton
  class MockResolvedServicesProvider(descriptor: Descriptor, changeServer: Descriptor => Descriptor) extends Provider[ResolvedServices] {
    @Inject var serverBuilder: ServerBuilder = _
    @Inject var mockService: MockServiceImpl = _

    lazy val get = {
      val resolved = serverBuilder.resolveDescriptorToImpl(descriptor, mockService)
      val changed = changeServer(resolved)
      new ResolvedServices(Seq(ResolvedService(classOf[MockService], changed)))
    }
  }

  def change(callId: CallId)(changer: Call[_, _, _] => Call[_, _, _]): Descriptor => Descriptor = { descriptor =>
    val newEndpoints = descriptor.calls.asScala.map { endpoint =>
      if (endpoint.callId == callId) {
        changer(endpoint)
      } else endpoint
    }
    descriptor.replaceAllCalls(TreePVector.from(newEndpoints.asJava))
  }

  def failingRequestSerializer: Call[_, _, _] => Call[_, _, _] = { endpoint =>
    if (endpoint.requestSerializer.isInstanceOf[StreamedMessageSerializer[_]]) {
      endpoint.asInstanceOf[Call[Any, JSource[Any, _], Any]]
        .withRequestSerializer(new DelegatingStreamedMessageSerializer(failingSerializer))
    } else {
      endpoint.asInstanceOf[Call[Any, Any, Any]].withRequestSerializer(failingSerializer)
    }
  }

  def failingResponseSerializer: Call[_, _, _] => Call[_, _, _] = { endpoint =>
    if (endpoint.responseSerializer.isInstanceOf[StreamedMessageSerializer[_]]) {
      endpoint.asInstanceOf[Call[Any, Any, JSource[Any, _]]]
        .withResponseSerializer(new DelegatingStreamedMessageSerializer(failingSerializer))
    } else {
      endpoint.asInstanceOf[Call[Any, Any, Any]].withResponseSerializer(failingSerializer)
    }
  }

  def failingSerializer = new StrictMessageSerializer[Any] {
    val failedSerializer = new NegotiatedSerializer[Any, ByteString] {
      override def serialize(messageEntity: Any): ByteString = throw new SerializationException("failed serialize")
    }
    override def deserializer(messageHeader: MessageProtocol) = new NegotiatedDeserializer[Any, ByteString] {
      override def deserialize(wire: ByteString): AnyRef = throw new DeserializationException("failed deserialize")
    }
    override def serializerForResponse(acceptedMessageHeaders: util.List[MessageProtocol]) =
      failedSerializer
    override def serializerForRequest() =
      failedSerializer
  }

  def failingRequestNegotiation: Call[_, _, _] => Call[_, _, _] = { endpoint =>
    if (endpoint.requestSerializer.isInstanceOf[StreamedMessageSerializer[_]]) {
      endpoint.asInstanceOf[Call[Any, JSource[Any, _], Any]]
        .withRequestSerializer(new DelegatingStreamedMessageSerializer(failingNegotiation))
    } else {
      endpoint.asInstanceOf[Call[Any, Any, Any]].withRequestSerializer(failingNegotiation)
    }
  }

  def failingResponseNegotation: Call[_, _, _] => Call[_, _, _] = { endpoint =>
    if (endpoint.responseSerializer.isInstanceOf[StreamedMessageSerializer[_]]) {
      endpoint.asInstanceOf[Call[Any, Any, JSource[Any, _]]]
        .withResponseSerializer(new DelegatingStreamedMessageSerializer(failingNegotiation))
    } else {
      endpoint.asInstanceOf[Call[Any, Any, Any]].withResponseSerializer(failingNegotiation)
    }
  }

  def failingNegotiation = new StrictMessageSerializer[Any] {
    override def serializerForRequest(): NegotiatedSerializer[Any, ByteString] =
      throw new NotImplementedError("Can't fail negotiation for request")

    override def deserializer(messageHeader: MessageProtocol): NegotiatedDeserializer[Any, ByteString] =
      throw new UnsupportedMediaType(messageHeader, new MessageProtocol().withContentType("unsupported"))

    override def serializerForResponse(acceptedMessageHeaders: util.List[MessageProtocol]): NegotiatedSerializer[Any, ByteString] = {
      throw new NotAcceptable(acceptedMessageHeaders, new MessageProtocol().withContentType("not accepted"))
    }
  }

  def failingServiceCall: Call[_, _, _] => Call[_, _, _] = { endpoint =>
    endpoint.asInstanceOf[Call[Any, Any, Any]].`with`(new ServiceCall[Any, Any, Any] {
      override def invoke(id: Any, request: Any): CompletionStage[Any] = throw new RuntimeException("service call failed")
    })
  }

  def asyncFailingServiceCall: Call[_, _, _] => Call[_, _, _] = { endpoint =>
    endpoint.asInstanceOf[Call[Any, Any, Any]].`with`(new ServiceCall[Any, Any, Any] {
      override def invoke(id: Any, request: Any): CompletionStage[Any] =
        Future.failed[Any](new RuntimeException("service call failed")).toJava
    })
  }

  def failingStreamedServiceCall: Call[_, _, _] => Call[_, _, _] = { endpoint =>
    // If the response is not streamed, then just return a failing service call
    if (endpoint.responseSerializer.isInstanceOf[StreamedMessageSerializer[_]]) {
      endpoint.asInstanceOf[Call[Any, Any, JSource[Any, _]]]
        .`with`(new ServiceCall[Any, Any, JSource[Any, _]] {
          override def invoke(id: Any, request: Any): CompletionStage[JSource[Any, _]] = {
            CompletableFuture.completedFuture(request match {
              case stream: JSource[Any, _] =>
                (stream.asScala via AkkaStreams.ignoreAfterCancellation map { _ =>
                  throw new RuntimeException("service call failed")
                }).asJava
              case _ =>
                JSource.failed(throw new RuntimeException("service call failed"))
            })
          }
        })
    } else {
      failingServiceCall(endpoint)
    }
  }

}
