package models

import javax.inject.Inject

import utils.Hash

case class Agent(id: String,
                 name: String,
                 qualite: String,
                 email: String,
                 key: String,
                 admin: Boolean,
                 instructor: Boolean,
                 finalReview: Boolean)

@javax.inject.Singleton
class AgentService @Inject()(configuration: play.api.Configuration) {
  private lazy val cryptoSecret = configuration.underlying.getString("play.crypto.secret")

  private lazy val agents = List(
    Agent("admin", "Jean Paul", "service développement durable", "jean.paul.durable@example.com", Hash.sha256(s"${cryptoSecret}Iz7h_09MqC"), true, true, false),
    Agent("verts", "Jeanne D'arc", "direction des espaces verts-propreté", "jeanne.d-arc.verts@example.com", Hash.sha256(s"${cryptoSecret}nYX3aeNs"), false, false, false),
    Agent("voirie", "Jeanne D'arc", "direction de la voirie", "jeanne.d-arc.voirie@example.com", Hash.sha256(s"${cryptoSecret}o-35qG5T"), false, false, false),
    Agent("public", "Jeanne D'arc", "direction occupation du domaine public", "jeanne.d-arc.public@example.com", Hash.sha256(s"{cryptoSecret}_eMMIKc9"), false, false, false),
    Agent("patrimoine", "Jeanne D'arc", "direction du patrimoine", "jeanne.d-arc.patrimoine@example.com", Hash.sha256(s"${cryptoSecret}gfI6nQ7"), false, false, false),
    Agent("elu", "Richard Dupont", "adjoint au maire, Transition écologique et énergétique, Parcs et jardins", "jdupont.elu@example.com", Hash.sha256(s"${cryptoSecret}Bv5_75R"), true, false, true)
  )

  def all() = agents

  def byId(id: String) = agents.find(_.id == id)

  def byKey(key: String) = agents.find(_.key == key)
}