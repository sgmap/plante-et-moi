package controllers

import javax.inject._

import play.api._
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import models._
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._
import play.api.libs.mailer.MailerClient
import actions.LoginAction

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

import play.api.libs.mailer._
import org.apache.commons.mail.EmailAttachment

@Singleton
class ApplicationController @Inject() (ws: WSClient, configuration: play.api.Configuration, reviewService: ReviewService, applicationExtraService: ApplicationExtraService, mailerClient: MailerClient, agentService: AgentService, loginAction: LoginAction) extends Controller {

  private def getCity(request: RequestHeader) =
    request.session.get("city").getOrElse("Arles")

  private def currentAgent(request: RequestHeader): Agent = {
    val id = request.session.get("agentId").getOrElse("admin")
    agents.find(_.id == id).get
  }

  private lazy val agents = agentService.all()

  private lazy val typeformId = configuration.underlying.getString("typeform.id")
  private lazy val typeformKey = configuration.underlying.getString("typeform.key")

  def mapArlesTypeformJsonToApplication(answer: JsValue): models.Application = {
    val selectedAddress = (answer \ "hidden" \ "address").asOpt[String].getOrElse("12 rue de la demo")
    val address = (answer \ "answers" \ "textfield_38117960").asOpt[String].getOrElse(selectedAddress)
    val `type` = (answer \ "hidden" \ "type").asOpt[String].map(_.stripPrefix("projet de ").stripSuffix(" fleuris").capitalize).getOrElse("Inconnu")
    val email = (answer \ "answers" \ "email_38072800").asOpt[String].getOrElse("non_renseigné@example.com")
    implicit val dateReads = Reads.jodaDateReads("yyyy-MM-dd HH:mm:ss")
    val date = (answer \ "metadata" \ "date_submit").as[DateTime]
    val firstname = (answer \ "answers" \ "textfield_38072796").asOpt[String].getOrElse("John")
    val lastname = (answer \ "answers" \ "textfield_38072795").asOpt[String].getOrElse("Doe")
    val name = s"$firstname $lastname"
    val id = (answer \ "token").as[String]
    val phone = (answer \ "answers" \ "textfield_38072797").asOpt[String]
    val lat = (answer \ "hidden" \ "lat").as[String].toDouble
    val lon = (answer \ "hidden" \ "lon").as[String].toDouble
    val coordinates = Coordinates(lat, lon)
    var fields = Map[String,String]()
    (answer \ "answers" \ "textfield_41115782").asOpt[String].map { answer =>
      fields += "Espéces de plante grimpante" -> answer
    }
    (answer \ "answers" \ "textfield_41934708").asOpt[String].map { answer =>
      fields += "Forme" -> answer
    }
    (answer \ "answers" \ "list_42010898_choice").asOpt[String].map { answer =>
      fields += "Couleur" -> answer
    }
    (answer \ "answers" \ "list_42010898_other").asOpt[String].map { answer =>
      fields += "Couleur" -> answer
    }
    (answer \ "answers" \ "textfield_41934830").asOpt[String].map { answer =>
      fields += "Matériaux" -> answer
    }
    (answer \ "answers" \ "list_41934920_choice").asOpt[String].map { answer =>
      fields += "Position" -> answer
    }
    (answer \ "answers" \ "list_40487664_choice").asOpt[String].map { answer =>
      fields += "Collectif" -> "Oui"
    }
    (answer \ "answers" \ "textfield_40930276").asOpt[String].map { answer =>
      fields += "Nom du collectif" -> answer
    }
    var files = ListBuffer[String]()
    (answer \ "answers" \ "fileupload_40488503").asOpt[String].map { croquis =>
      files.append(croquis.split('?')(0))
    }
    (answer \ "answers" \ "fileupload_40489342").asOpt[String].map { image =>
      files.append(image.split('?')(0))
    }

    models.Application(id, name, email, `type`, address, date, coordinates, phone, fields, files.toList)
  }

  def projects(city: String) =
    ws.url(s"https://api.typeform.com/v1/form/$typeformId")
      .withQueryString("key" -> typeformKey,
        "completed" -> "true",
        "order_by" -> "date_submit,desc",
        "limit" -> "20").get().map { response =>
      val json = response.json
      val totalShowing = (json \ "stats" \ "responses" \ "completed").as[Int]
      val totalCompleted = (json \ "stats" \ "responses" \ "showing").as[Int]

      val responses = (json \ "responses").as[List[JsValue]].filter { answer =>
        (answer \ "hidden" \ "city").get == Json.toJson(city) &&
          (answer \ "hidden" \ "lat").get != JsNull &&
          (answer \ "hidden" \ "lon").get != JsNull
      } .map(mapArlesTypeformJsonToApplication)
      responses.map { application =>
        (application, applicationExtraService.findByApplicationId(application.id), reviewService.findByApplicationId(application.id))
      }
    }

  def getImage(url: String) = loginAction.async { implicit request =>
    var request = ws.url(url.replaceFirst(":443", ""))
    if(url.contains("api.typeform.com")) {
      request = request.withQueryString("key" -> typeformKey)
    }
    request.get().map { fileResult =>
      val contentType = fileResult.header("Content-Type").getOrElse("text/plain")
      val filename = url.split('/').last
      Ok(fileResult.bodyAsBytes).withHeaders("Content-Disposition" -> s"attachment; filename=$filename").as(contentType)
    }
  }

  def all = loginAction.async { implicit request =>
    projects(getCity(request)).map { responses =>
      Ok(views.html.allApplications(responses, currentAgent(request), agents.filter { agent => !agent.instructor }.length))
    }
  }

  def map = loginAction.async { implicit request =>
    val city = getCity(request)
    projects(city).map { responses =>
      Ok(views.html.mapApplications(city, responses, currentAgent(request)))
    }
  }

  def my = loginAction.async { implicit request =>
    val agent = currentAgent(request)
    projects(getCity(request)).map { responses =>
      val afterFilter = responses.filter { response =>
        response._2.status == "En cours" &&
          !response._3.exists { _.agentId == agent.id }
      }
      Ok(views.html.myApplications(afterFilter, currentAgent(request)))
    }
  }

  def show(id: String) = loginAction.async { implicit request =>
    val agent = currentAgent(request)
    applicationById(id, getCity(request)).map {
        case None =>
          NotFound("")
        case Some(application) =>
          val reviews = reviewService.findByApplicationId(id)
              .map { review =>
                review -> agents.find(_.id == review.agentId).get
              }
          Ok(views.html.application(application._1, agent, reviews, application._2))
    }
  }

  private def applicationById(id: String, city: String) =
    projects(city).map { _.find { _._1.id == id } }


  def changeCity(newCity: String) = Action { implicit request =>
    Redirect(routes.ApplicationController.login()).withSession("city" -> newCity)
  }

  def disconnectAgent() = Action { implicit request =>
    Redirect(routes.ApplicationController.login()).withSession(request.session - "agentId")
  }

  def login() = Action { implicit request =>
    Ok(views.html.login(agents, getCity(request)))
  }

  case class ReviewData(favorable: Boolean, comment: String)
  val reviewForm = Form(
    mapping(
      "favorable" -> boolean,
      "comment" -> text
    )(ReviewData.apply)(ReviewData.unapply)
  )

  def addReview(applicationId: String) = loginAction.async { implicit request =>
    reviewForm.bindFromRequest.fold(
      formWithErrors => {
        Future.successful(BadRequest(""))
      },
      reviewData => {
        val city = getCity(request)
        val agent = currentAgent(request)
        val review = Review(applicationId, agent.id, DateTime.now(), reviewData.favorable, reviewData.comment)
        Future(reviewService.insertOrUpdate(review)).map { _ =>
          Redirect(routes.ApplicationController.show(applicationId))
        }
      }
    )
  }

  def updateStatus(id: String, status: String) = loginAction.async { implicit request =>
    applicationById(id, getCity(request)).map {
      case None =>
        NotFound("")
      case Some((application, applicationExtra, _)) =>
        if(status == "En cours" && applicationExtra.status != "En cours") {
          agents.filter { agent => !agent.instructor && !agent.finalReview }.foreach(sendNewApplicationEmailToAgent(application, request))
        }
        applicationExtraService.insertOrUpdate(applicationExtra.copy(status = status))
        Redirect(routes.ApplicationController.show(id))
    }
  }

  private def sendNewApplicationEmailToAgent(application: models.Application, request: RequestHeader)(agent: Agent) = {
    var url = s"${routes.ApplicationController.show(application.id).absoluteURL()(request)}?key=${agent.key}"
    val email = Email(
      s"Nouvelle demande de permis de végétalisation: ${application.address}",
      "Plante et Moi <administration@plante-et-moi.fr>",
      Seq(s"${agent.name} <${agent.email}>"),
      bodyText = Some(s"""Bonjour ${agent.name},
                    |
                    |Nous avons besoin de votre avis pour une demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).
                    |Vous pouvez voir la demande et laisser mon avis en ouvrant la page suivante:
                    |${url}
                    |
                    |Merci de votre aide,
                    |Si vous avez des questions, n'hésitez pas à nous contacter en répondant à ce mail""".stripMargin),
      bodyHtml = Some(
        s"""<html>
           |<body>
           | Bonjour ${agent.name}, <br>
           | <br>
           | Nous avons besoin de votre avis pour une demande de végétalisation au ${application.address} <br>
           | (c'est un projet de ${application._type}).<br>
           |<a href="${url}">Vous pouvez voir la demande et laisser mon avis en cliquant ici</a><br>
           | <br>
           | Merci de votre aide, <br>
           | Si vous avez des questions, n'hésitez pas à nous contacter en répondant à ce mail
           |</body>
           |</html>""".stripMargin)
    )
    mailerClient.send(email)
  }
}