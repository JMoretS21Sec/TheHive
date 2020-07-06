package org.thp.thehive.services

import java.util.Date

import gremlin.scala._
import javax.inject.{Inject, Named, Provider, Singleton}
import org.thp.scalligraph.EntitySteps
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.steps.{Traversal, TraversalLike, VertexSteps}
import org.thp.thehive.models.{TaskStatus, _}
import play.api.libs.json.{JsNull, JsObject, Json}

import scala.util.{Failure, Success, Try}

@Singleton
class TaskSrv @Inject() (caseSrvProvider: Provider[CaseSrv], auditSrv: AuditSrv)(
    implicit @Named("with-thehive-schema") db: Database
) extends VertexSrv[Task, TaskSteps] {

  lazy val caseSrv: CaseSrv = caseSrvProvider.get
  val caseTemplateTaskSrv   = new EdgeSrv[CaseTemplateTask, CaseTemplate, Task]
  val taskUserSrv           = new EdgeSrv[TaskUser, Task, User]
  val taskLogSrv            = new EdgeSrv[TaskLog, Task, Log]

  def create(e: Task, owner: Option[User with Entity])(implicit graph: Graph, authContext: AuthContext): Try[RichTask] =
    for {
      task <- createEntity(e)
      _    <- owner.map(taskUserSrv.create(TaskUser(), task, _)).flip
    } yield RichTask(task, owner)

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): TaskSteps = new TaskSteps(raw)

  def isAvailableFor(taskId: String)(implicit graph: Graph, authContext: AuthContext): Boolean =
    getByIds(taskId).visible(authContext).exists()

  def unassign(task: Task with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(task).unassign()
    auditSrv.task.update(task, Json.obj("assignee" -> JsNull))
  }

  def cascadeRemove(task: Task with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      share <- get(task).share(authContext.organisation).getOrFail("Task")
      _     <- auditSrv.task.delete(task, share)
    } yield get(task).remove()

  override def update(
      steps: TaskSteps,
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(TaskSteps, JsObject)] =
    auditSrv.mergeAudits(super.update(steps, propertyUpdaters)) {
      case (taskSteps, updatedFields) =>
        for {
          t <- taskSteps.newInstance().getOrFail()
          _ <- auditSrv.task.update(t, updatedFields)
        } yield ()
    }

  /**
    * Tries to update the status of a task with related fields
    * according the status value if empty
    * @param t the task to update
    * @param o the potential owner
    * @param s the status to set
    * @param graph db
    * @param authContext auth db
    * @return
    */
  def updateStatus(t: Task with Entity, o: User with Entity, s: TaskStatus.Value)(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[Task with Entity] = {
    def setStatus(): Try[Task with Entity] = get(t).updateOne("status" -> s)

    s match {
      case TaskStatus.Cancel | TaskStatus.Waiting => setStatus()
      case TaskStatus.Completed =>
        t.endDate.fold(get(t).updateOne("status" -> s, "endDate" -> Some(new Date())))(_ => setStatus())

      case TaskStatus.InProgress =>
        for {
          _       <- get(t).assignee.headOption().fold(assign(t, o))(_ => Success(()))
          updated <- t.startDate.fold(get(t).updateOne("status" -> s, "startDate" -> Some(new Date())))(_ => setStatus())
        } yield updated

      case _ => Failure(new Exception(s"Invalid TaskStatus $s for update"))
    }
  }

  def assign(task: Task with Entity, user: User with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    get(task).unassign()
    for {
      _ <- taskUserSrv.create(TaskUser(), task, user)
      _ <- auditSrv.task.update(task, Json.obj("assignee" -> user.login))
    } yield ()
  }
}

@EntitySteps[Task]
class TaskSteps(raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph) extends VertexSteps[Task](raw) {

  def visible(implicit authContext: AuthContext): TaskSteps = newInstance(
    raw.filter(
      _.inTo[ShareTask]
        .inTo[OrganisationShare]
        .has(Key("name") of authContext.organisation)
    )
  )

  override def newInstance(newRaw: GremlinScala[Vertex]): TaskSteps = new TaskSteps(newRaw)
  override def newInstance(): TaskSteps                             = new TaskSteps(raw.clone())

  def active: TaskSteps = newInstance(raw.filterNot(_.has(Key("status") of "Cancel")))

  def can(permission: Permission)(implicit authContext: AuthContext): TaskSteps =
    newInstance(
      raw.filter(
        _.inTo[ShareTask]
          .filter(_.outTo[ShareProfile].has(Key("permissions") of permission))
          .inTo[OrganisationShare]
          .inTo[RoleOrganisation]
          .filter(_.outTo[RoleProfile].has(Key("permissions") of permission))
          .inTo[UserRole]
          .has(Key("login") of authContext.userId)
      )
    )

  def `case`: CaseSteps = new CaseSteps(raw.inTo[ShareTask].outTo[ShareCase].dedup)

  def caseTemplate = new CaseTemplateSteps(raw.inTo[CaseTemplateTask])

  def caseTasks: TaskSteps = this.filter(_.inToE[ShareTask])

  def caseTemplateTasks: TaskSteps = this.filter(_.inToE[CaseTemplateTask])

  def logs: LogSteps = new LogSteps(raw.outTo[TaskLog])

  def assignee: UserSteps = new UserSteps(raw.outTo[TaskUser])

  def unassigned: TaskSteps = this.not(_.outToE[TaskUser])

  def organisations = new OrganisationSteps(raw.inTo[ShareTask].inTo[OrganisationShare])
  def organisations(permission: Permission) =
    new OrganisationSteps(raw.inTo[ShareTask].filter(_.outTo[ShareProfile].has(Key("permissions") of permission)).inTo[OrganisationShare])

  def assignableUsers(implicit authContext: AuthContext): UserSteps =
    organisations(Permissions.manageTask)
      .visible
      .users(Permissions.manageTask)
      .dedup

  def richTask: Traversal[RichTask, RichTask] =
    Traversal(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[TaskUser].fold))
        )
        .map {
          case (task, user) =>
            RichTask(
              task.as[Task],
              atMostOneOf(user).map(_.as[User])
            )
        }
    )

  def richTaskWithCustomRenderer[A](entityRenderer: TaskSteps => TraversalLike[_, A]): Traversal[(RichTask, A), (RichTask, A)] =
    Traversal(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[TaskUser].fold))
            .and(By(entityRenderer(newInstance(__[Vertex])).raw))
        )
        .map {
          case (task, user, renderedEntity) =>
            RichTask(
              task.as[Task],
              atMostOneOf(user).map(_.as[User])
            ) -> renderedEntity
        }
    )

  def unassign(): Unit = this.outToE[TaskUser].remove()

  def shares: ShareSteps = new ShareSteps(raw.inTo[ShareTask])

  def share(implicit authContext: AuthContext): ShareSteps = share(authContext.organisation)

  def share(organistionName: String): ShareSteps =
    new ShareSteps(this.inTo[ShareTask].filter(_.inTo[OrganisationShare].has("name", organistionName)).raw)
}
