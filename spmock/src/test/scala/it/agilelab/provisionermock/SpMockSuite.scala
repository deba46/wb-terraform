package it.agilelab.provisionermock

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.comcast.ip4s.{ Host, Port }
import it.agilelab.provisionermock.config.SpMockConfiguration
import it.agilelab.spinframework.app.SpecificProvisioner
import it.agilelab.spinframework.app.api.generated.definitions.{
  ProvisioningRequest,
  ProvisioningStatus,
  ValidationResult
}
import it.agilelab.spinframework.app.config.FrameworkDependencies
import it.agilelab.spinframework.app.features.support.test.LocalHttpClient
import org.http4s.ember.server.EmberServerBuilder
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should

trait SpMockSuite extends AnyFlatSpec with should.Matchers with BeforeAndAfterAll {
  // By using "localhost", tests also run in Jenkins pipelines
  private val interface: String = "localhost"
  private val port              = SpMockConfiguration().getInt(SpMockConfiguration.networking_httpServer_port)

  val httpClient = new LocalHttpClient(port)

  def specificProvisioner: SpecificProvisioner

  var shutdown: IO[Unit]          = IO.unit
  implicit val runtime: IORuntime = cats.effect.unsafe.IORuntime.global

  override protected def beforeAll(): Unit = {
    val frameworkDependencies = new FrameworkDependencies(specificProvisioner.specificProvisionerDependencies)
    val server                = EmberServerBuilder
      .default[IO]
      .withPort(Port.fromInt(port).get)
      .withHost(Host.fromString(interface).get)
      .withHttpApp(frameworkDependencies.httpApp)
      .build
    shutdown = server.allocated.unsafeRunSync()._2
  }

  override protected def afterAll(): Unit = {
    shutdown.unsafeRunSync()
    Thread.sleep(1000)
  }

  "The spmock" should "return an health-check response" in {
    val healthHttpClient    = new LocalHttpClient(port, Some(""))
    val healthCheckResponse = healthHttpClient.get(
      endpoint = "/health",
      bodyClass = classOf[String]
    )

    healthCheckResponse.status shouldBe 200
  }

  it should "validate a provision request" in {
    val descriptor =
      """
      region: west-europe
      container:
        name: name-container
        size: Medium
    """

    val validateResponse: ValidationResult = httpClient
      .post(
        endpoint = "/validate",
        request = ProvisioningRequest(descriptor),
        bodyClass = classOf[ValidationResult]
      )
      .body

    validateResponse.valid shouldBe true
    validateResponse.error shouldBe null
  }

  it should "accept an unprovision request" in {
    val provisioningStatusResponse = httpClient.post(
      endpoint = "/unprovision",
      request = ProvisioningRequest("container: somename"),
      bodyClass = classOf[ProvisioningStatus]
    )

    provisioningStatusResponse.status shouldBe 200
    provisioningStatusResponse.body.status shouldBe ProvisioningStatus.Status.Completed
  }

}
