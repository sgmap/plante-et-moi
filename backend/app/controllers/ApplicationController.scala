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
import services.ApplicationService

@Singleton
class ApplicationController @Inject() (ws: WSClient,
                                       configuration: play.api.Configuration,
                                       reviewService: ReviewService,
                                       mailerClient: MailerClient,
                                       agentService: AgentService,
                                       loginAction: LoginAction,
                                       applicationService: ApplicationService) extends Controller {

  private def getCity(request: RequestHeader) =
    request.session.get("city").getOrElse("arles").toLowerCase()

  private def currentAgent(request: RequestHeader): Agent = {
    val id = request.session.get("agentId").getOrElse("admin")
    agents.find(_.id == id).get
  }

  private lazy val agents = agentService.all()

  private lazy val typeformKey = configuration.underlying.getString("typeform.key")

  def projects(city: String) = Future.successful {
    applicationService.findByCity(city).map { application =>
      (application, reviewService.findByApplicationId(application.id))
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
      val numberOrReviewNeeded = agents.count { agent => !agent.instructor }
      Ok(views.html.allApplications(responses, currentAgent(request), numberOrReviewNeeded))
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
        response._1.status == "En cours" &&
          !response._2.exists { _.agentId == agent.id }
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
          Ok(views.html.application(application._1, agent, reviews))
    }
  }

  private def applicationById(id: String, city: String) =
    projects(city).map { _.find { _._1.id == id } }


  def changeCity(newCity: String) = Action { implicit request =>
    Redirect(routes.ApplicationController.login()).withSession("city" -> newCity.toLowerCase)
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
          Redirect(routes.ApplicationController.my()).flashing("success" -> "Votre avis a bien été pris en compte.")
        }
      }
    )
  }

  def updateStatus(id: String, status: String) = loginAction.async { implicit request =>
    applicationById(id, getCity(request)).map {
      case None =>
        NotFound("")
      case Some((application, _)) =>
        var message = "Le status de la demande a été mis à jour"
        if(status == "En cours" && application.status != "En cours") {
          agents.filter { agent => !agent.instructor && !agent.finalReview }.foreach(sendNewApplicationEmailToAgent(application, request))
          message = "Le status de la demande a été mis à jour, un mail a été envoyé aux agents pour obtenir leurs avis."
        }
        applicationService.updateStatus(application.id, status)
        Redirect(routes.ApplicationController.all()).flashing("success" -> message)
    }
  }

  private def sendNewApplicationEmailToAgent(application: models.Application, request: RequestHeader)(agent: Agent) = {
    val url = s"${routes.ApplicationController.show(application.id).absoluteURL()(request)}?key=${agent.key}"
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