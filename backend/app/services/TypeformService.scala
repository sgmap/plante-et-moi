package services

import javax.inject._

import akka.actor._
import controllers.routes
import models.{Application, Coordinates}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.libs.ws.WSClient

import scala.collection.mutable.ListBuffer
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.mailer.{Email, MailerClient}
import play.api.mvc.RequestHeader
import utils.Hash

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class TypeformQuestion(id: String, question: String, field_id: Int)
case class TypeformStats(responses: Map[String,Int])
case class TypeformMetadata(browser: String,
                            platform: String,
                            date_land: DateTime,
                            date_submit: DateTime,
                            user_agent: String,
                            referer: String,
                            network_id: String)
case class TypeformResponse(completed: String, token: String, metadata: TypeformMetadata, hidden: Map[String, Option[String]], answers: Map[String, String])
case class TypeformResult(http_status: Int, stats: TypeformStats, questions: List[TypeformQuestion], responses: List[TypeformResponse])


@Singleton
class TypeformService @Inject()(system: ActorSystem, configuration: play.api.Configuration, ws: WSClient, applicationService: ApplicationService, mailerClient: MailerClient) {


  private implicit val optionMap = Reads[Option[String]]{
    case JsString(s) => JsSuccess(Some(s))
    case JsNull => JsSuccess(None)
    case _ => JsError("Not a optionnal String")
  }
  //private implicit val hiddenMap = Reads.mapReads[Option[String]]
  private implicit val dateReads = Reads.jodaDateReads("yyyy-MM-dd HH:mm:ss")
  private implicit val metadataReads = Json.reads[TypeformMetadata]
  private implicit val questionReads = Json.reads[TypeformQuestion]
  private implicit val statsReads = Json.reads[TypeformStats]
  private implicit val responseReads = Json.reads[TypeformResponse]
  private implicit val resultReads = Json.reads[TypeformResult]


  def getForm(id: String, key: String, completed: Boolean, limit: Int = 20, orderBy: String = "date_submit,desc") = {
    val request = ws.url(s"https://api.typeform.com/v1/form/$id")
      .withQueryString("key" -> key,
        "completed" -> s"$completed",
        "order_by" -> orderBy,
        "limit" -> s"$limit")
    Logger.info(s"Get typeform data ${request.url}")
    request.get()
  }

  private lazy val typeformIds = configuration.underlying.getString("typeform.ids").split(",")
  private lazy val typeformKey = configuration.underlying.getString("typeform.key")

  private val refresh = configuration.getMilliseconds("typeform.refresh") match {
    case Some(t) => t millis
    case None => 2 minutes
  }
  private val delay = 0 minutes

  private val scheduledTask = system.scheduler.schedule(delay, refresh)(refreshTask)

  def refreshTask = {
    val responses = Future.reduce(typeformIds.map{ id =>
      getForm(id, typeformKey, true, 100).map { response =>
        val result = response.json.validate[TypeformResult]
        var applications = List[Application]()
        result match {
          case success: JsSuccess[TypeformResult] =>
            Logger.info(s"TypeformService: convert data for $id")
            val result = success.get
            applications = result.responses.map(mapResponseToApplication(result.questions))
          case error: JsError =>
            val errorString = JsError.toJson(error).toString()
            Logger.error(s"TypeformService: json errors for $id ${errorString}")
        }
        applications
      }
    })(_ ++ _)
    responses.onComplete {
      case Failure(ex) =>
          Logger.error("Error occured when retrieve data from typeform", ex)
      case Success(applications) =>
        applications.foreach { application =>
          val app = applicationService.findByApplicationId(application.id)
          if (app.isEmpty) {
            Logger.info(s"Import application for ${application.address}")
            sendNewApplicationEmail(application)
            applicationService.insert(application)
          }
        }
    }
  }

  private def sendNewApplicationEmail(application: models.Application) = {
    val email = Email(
      s"Nous avons bien reçu votre projet de ${application._type} à l'adresse: ${application.address}",
      "Plante et Moi <administration@plante-et-moi.fr>",
      Seq(s"${application.name} <${application.email}>"),
      bodyText = Some(s"""Bonjour ${application.name},
                         |
                         |Nous avons bien reçu votre demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).
                         |
                         |L’autorisation suivra dans quelques semaines, accompagnée de la charte rappelant vos engagements ainsi que la liste des plantes proscrites et des plantes recommandées.
                         |
                         |Merci de votre demande,
                         |Si vous avez des questions, n'hésitez pas à nous contacter""".stripMargin),
      bodyHtml = Some(
        s"""<html>
           |<body>
           | Bonjour ${application.name}, <br>
           | <br>
           | Nous avons bien reçu votre demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).<br>
           | <br>
           | L’autorisation suivra dans quelques semaines, accompagnée de la charte rappelant vos engagements ainsi que la liste des plantes proscrites et des plantes recommandées. <br>
           | <br>
           | Merci de votre demande, <br>
           | Si vous avez des questions, n'hésitez pas à nous contacter
           |</body>
           |</html>""".stripMargin)
    )
    Logger.info(s"Send mail to ${application.email}")
    mailerClient.send(email)
  }

  def mapResponseToApplication(questions: List[TypeformQuestion])(response: TypeformResponse) = {
    val _type = response.hidden("type").get.stripPrefix("projet de ").stripSuffix(" fleuris").capitalize
    val lat = response.hidden("lat").get.toDouble
    val lon = response.hidden("lon").get.toDouble
    val coordinates = Coordinates(lat, lon)
    val city = response.hidden("city").get.toLowerCase()
    val typeformId = response.token
    val date = response.metadata.date_submit

    var address = response.hidden("address").get
    var email = "inconnue@example.com"
    var firstname = "John"
    var lastname = "Doe"
    var phone: Option[String] = None

    var fields = mutable.Map[String,String]()
    var files = ListBuffer[String]()
    response.answers.foreach { answer =>
      val question = questions.find(_.id == answer._1)
      (answer._1, question) match {
        case (id, _) if id.startsWith("fileupload_") =>
          files += answer._2.split('?')(0)
        case (id, _) if id.startsWith("email_") =>
          email = answer._2
        case (id, Some(question)) if id.startsWith("textfield_") && question.question.toLowerCase.contains("adresse de votre") =>
          address = answer._2
        case (id, Some(question)) if id.startsWith("textfield_") && question.question.toLowerCase.contains("prénom") =>
          firstname = answer._2
        case (id, Some(question)) if id.startsWith("textfield_") && question.question.toLowerCase.contains("nom") =>
          lastname = answer._2
        case (id, Some(question)) if id.startsWith("textfield_") && question.question.toLowerCase.contains("téléphone") =>
          phone = Some(answer._2)
        case (id, Some(question)) if id.startsWith("yesno_") =>
          val answerString = answer._2 match {
            case "1" => "Oui"
            case "0" => "Non"
            case _ => "???"
          }
          fields += question.question -> answerString
        case (_, Some(question)) =>
          val previous = fields.get(question.question).map(old => s"$old, ${answer._2}").getOrElse( answer._2)
          fields += question.question -> previous
        case _ =>
      }
    }
    var source = "typeform"
    var applicationId = Hash.sha256(s"$source$typeformId")
    models.Application(applicationId, city, "Nouvelle", firstname, lastname, email, _type, address, date, coordinates, source, typeformId, phone, fields.toMap, files.toList)
  }
}