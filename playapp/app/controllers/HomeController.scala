package controllers

import javax.inject.{ Inject, Singleton }

import org.flywaydb.play.Flyways
import play.api.Logger
import play.api.mvc._

@Singleton
class HomeController @Inject() (flyways: Flyways) extends Controller {

  private val logger = Logger(classOf[HomeController])

  def index = Action {
    Ok(views.html.index(flyways, "Your new application is ready."))
  }

  def hello = Action {
    Ok("Hello")
  }

}