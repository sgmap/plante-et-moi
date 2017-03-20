package models

import org.joda.time.DateTime

case class Application(id: String,
                       city: String,
                       status: String,
                       firstname: String,
                       lastname: String,
                       email: String,
                       _type: String,
                       address: String,
                       creationDate: DateTime,
                       coordinates: Coordinates,
                       source: String,
                       sourceId: String,
                       phone: Option[String] = None,
                       fields: Map[String, String] = Map(),
                       files: List[String] = List()) {
   val name = s"${firstname.capitalize} ${lastname.capitalize}"
   private def imageFilter(fileName: String) = List("jpg","jpeg","png").exists(fileName.toLowerCase().endsWith(_))
   def imagesFiles() = files.filter(imageFilter)
   def notImageFiles() = files.filter(!imageFilter(_))
}