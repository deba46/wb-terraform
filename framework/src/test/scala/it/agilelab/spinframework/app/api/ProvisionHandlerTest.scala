package it.agilelab.spinframework.app.api

import cats.effect.IO
import com.typesafe.config.Config
import io.circe.JsonObject
import io.circe.generic.auto._
import io.circe.syntax._
import it.agilelab.spinframework.app.api.generated.Resource
import it.agilelab.spinframework.app.api.generated.definitions.{
  DescriptorKind,
  Info,
  ProvisioningRequest,
  SystemError,
  ValidationError,
  ProvisioningStatus => PSDto
}
import it.agilelab.spinframework.app.api.helpers.HandlerTestBase
import it.agilelab.spinframework.app.api.mapping.ProvisioningInfoMapper.{ InnerInfoJson, OutputsWrapper }
import it.agilelab.spinframework.app.config.Configuration.provisionerConfig
import it.agilelab.spinframework.app.features.compiler.{ ErrorMessage, JsonDescriptor, TerraformOutput, YamlDescriptor }
import it.agilelab.spinframework.app.features.provision.{ ComponentToken, Provision, ProvisionResult }
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{ Method, Request, Response, Status }

class ProvisionHandlerTest extends HandlerTestBase {
  class ProvisionStub extends Provision {
    override def doProvisioning(yamlDescriptor: YamlDescriptor): ProvisionResult = ProvisionResult.completed()
    override def doUnprovisioning(yaml: YamlDescriptor): ProvisionResult         = ProvisionResult.completed()
    override def doUpdateAcl(
      jsonDescriptor: JsonDescriptor,
      refs: Set[String],
      cfg: Config = provisionerConfig
    ): ProvisionResult                                                           =
      ProvisionResult.completed()
  }

  "The server" should "return a 200 - COMPLETED" in {
    val provisionStub: Provision   = new ProvisionStub {
      override def doProvisioning(yamlDescriptor: YamlDescriptor): ProvisionResult = ProvisionResult.completed()
    }
    val handler                    = new SpecificProvisionerHandler(provisionStub, null, null)
    val response: IO[Response[IO]] = new Resource[IO]()
      .routes(handler)
      .orNotFound
      .run(
        Request(method = Method.POST, uri = uri"datamesh.specificprovisioner/v1/provision")
          .withEntity(ProvisioningRequest(DescriptorKind.ComponentDescriptor, "a-yaml-descriptor"))
      )
    val expected                   = new PSDto(PSDto.Status.Completed, "")

    check[PSDto](response, Status.Ok, Some(expected)) shouldBe true
  }

  it should "return a 202 with a component token" in {
    val provisionStub: Provision   = new ProvisionStub {
      override def doProvisioning(yamlDescriptor: YamlDescriptor): ProvisionResult =
        ProvisionResult.running(ComponentToken("some-token"))
    }
    val handler                    = new SpecificProvisionerHandler(provisionStub, null, null)
    val response: IO[Response[IO]] = new Resource[IO]()
      .routes(handler)
      .orNotFound
      .run(
        Request(method = Method.POST, uri = uri"datamesh.specificprovisioner/v1/provision")
          .withEntity(ProvisioningRequest(DescriptorKind.ComponentDescriptor, "a-yaml-descriptor"))
      )
    val expected                   = "some-token"

    check[String](response, Status.Accepted, Some(expected)) shouldBe true
  }

  it should "return a 400 with a list of errors" in {
    val errors                     = Seq(ErrorMessage("first error"), ErrorMessage("second error"))
    val provisionStub: Provision   = new ProvisionStub {
      override def doProvisioning(yamlDescriptor: YamlDescriptor): ProvisionResult = ProvisionResult.failure(errors)
    }
    val handler                    = new SpecificProvisionerHandler(provisionStub, null, null)
    val response: IO[Response[IO]] = new Resource[IO]()
      .routes(handler)
      .orNotFound
      .run(
        Request(method = Method.POST, uri = uri"datamesh.specificprovisioner/v1/provision")
          .withEntity(ProvisioningRequest(DescriptorKind.ComponentDescriptor, "a-yaml-descriptor"))
      )
    val expected                   = ValidationError(errors.map(_.description).toVector)

    check[ValidationError](response, Status.BadRequest, Some(expected)) shouldBe true
  }

  it should "return a 500 with meaningful error on provision exception" in {
    val provisionStub: Provision   = new ProvisionStub {
      override def doProvisioning(yamlDescriptor: YamlDescriptor): ProvisionResult =
        throw new IllegalArgumentException("error")
    }
    val handler                    = new SpecificProvisionerHandler(provisionStub, null, null)
    val response: IO[Response[IO]] = new Resource[IO]()
      .routes(handler)
      .orNotFound
      .run(
        Request(method = Method.POST, uri = uri"datamesh.specificprovisioner/v1/provision")
          .withEntity(ProvisioningRequest(DescriptorKind.ComponentDescriptor, "a-yaml-descriptor"))
      )
    val expected                   = SystemError("error")

    check[SystemError](response, Status.InternalServerError) shouldBe true
  }

  it should "return a 500 with meaningful error on unprovision exception" in {
    val provisionStub: Provision   = new ProvisionStub {
      override def doUnprovisioning(yamlDescriptor: YamlDescriptor): ProvisionResult =
        throw new IllegalArgumentException("error")
    }
    val handler                    = new SpecificProvisionerHandler(provisionStub, null, null)
    val response: IO[Response[IO]] = new Resource[IO]()
      .routes(handler)
      .orNotFound
      .run(
        Request(method = Method.POST, uri = uri"datamesh.specificprovisioner/v1/unprovision")
          .withEntity(ProvisioningRequest(DescriptorKind.ComponentDescriptor, "a-yaml-descriptor"))
      )
    val expected                   = SystemError("error")

    check[SystemError](response, Status.InternalServerError) shouldBe true
  }

  "The server" should "return a 200 - COMPLETED (with outputs)" in {
    val provisionStub: Provision   = new ProvisionStub {
      override def doProvisioning(yamlDescriptor: YamlDescriptor): ProvisionResult = {
        val outputs: Seq[TerraformOutput] = Seq(
          TerraformOutput("foo", "bar")
        )
        ProvisionResult.completed(outputs)
      }
    }
    val handler                    = new SpecificProvisionerHandler(provisionStub, null, null)
    val response: IO[Response[IO]] = new Resource[IO]()
      .routes(handler)
      .orNotFound
      .run(
        Request(method = Method.POST, uri = uri"datamesh.specificprovisioner/v1/provision")
          .withEntity(ProvisioningRequest(DescriptorKind.ComponentDescriptor, "a-yaml-descriptor"))
      )

    val expected = new PSDto(
      PSDto.Status.Completed,
      "",
      Some(Info(JsonObject.empty.asJson, OutputsWrapper(Map("foo" -> InnerInfoJson("bar"))).asJson))
    )

    check[PSDto](response, Status.Ok, Some(expected)) shouldBe true
  }

  "The server" should "return a 200 - COMPLETED (without outputs)" in {
    val provisionStub: Provision   = new ProvisionStub {
      override def doProvisioning(yamlDescriptor: YamlDescriptor): ProvisionResult = {
        val outputs: Seq[TerraformOutput] = Seq()
        ProvisionResult.completed(outputs)
      }
    }
    val handler                    = new SpecificProvisionerHandler(provisionStub, null, null)
    val response: IO[Response[IO]] = new Resource[IO]()
      .routes(handler)
      .orNotFound
      .run(
        Request(method = Method.POST, uri = uri"datamesh.specificprovisioner/v1/provision")
          .withEntity(ProvisioningRequest(DescriptorKind.ComponentDescriptor, "a-yaml-descriptor"))
      )

    val expected = new PSDto(PSDto.Status.Completed, "", None)

    check[PSDto](response, Status.Ok, Some(expected)) shouldBe true
  }

}
