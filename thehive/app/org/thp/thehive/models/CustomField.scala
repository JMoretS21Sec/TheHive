package org.thp.thehive.models

import org.apache.tinkerpop.gremlin.process.traversal.P
import org.thp.scalligraph._
import org.thp.scalligraph.controllers.{Output, Renderer}
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.PredicateOps
import org.thp.scalligraph.traversal.Traversal.Domain
import org.thp.scalligraph.traversal.{Traversal, TraversalOps}
import play.api.libs.functional.syntax._
import play.api.libs.json._

import java.util.Date
import scala.util.{Failure, Success, Try}

@BuildEdgeEntity[CustomFieldValue, CustomField]
case class CustomFieldValueCustomField()

@BuildVertexEntity
@DefineIndex(IndexType.standard, "elementId")
@DefineIndex(IndexType.standard, "name")
@DefineIndex(IndexType.standard, "stringValue")
@DefineIndex(IndexType.standard, "booleanValue")
@DefineIndex(IndexType.standard, "integerValue")
@DefineIndex(IndexType.standard, "floatValue")
@DefineIndex(IndexType.standard, "dateValue")
case class CustomFieldValue(
    elementId: EntityId,
    name: String,
    order: Option[Int] = None,
    stringValue: Option[String] = None,
    booleanValue: Option[Boolean] = None,
    integerValue: Option[Int] = None,
    floatValue: Option[Double] = None,
    dateValue: Option[Date] = None
)

object CustomFieldType {
  def withName(name: String): CustomFieldType[_] =
    name match {
      case "string"  => CustomFieldString
      case "integer" => CustomFieldInteger
      case "float"   => CustomFieldFloat
      case "boolean" => CustomFieldBoolean
      case "date"    => CustomFieldDate
      case other     => throw InternalError(s"Invalid CustomFieldType (found: $other, expected: string, integer, float, boolean or date)")
    }
  implicit val renderer: Renderer[CustomFieldType[_]] = new Renderer[CustomFieldType[_]] {
    override type O = String
    override def toOutput(value: CustomFieldType[_]): Output = Output(value.name)
    override def toValue(value: CustomFieldType[_]): String  = value.name
  }
  implicit val mapping: SingleMapping[CustomFieldType[_], String] =
    SingleMapping[CustomFieldType[_], String](toGraph = t => t.name, toDomain = withName)
}

sealed abstract class CustomFieldType[T] extends TraversalOps with PredicateOps {
  val name: String
  val format: Format[T]

  protected def fail(value: JsValue): Failure[Nothing] =
    Failure(BadRequestError(s"""Invalid value type for custom field.
                               |  Expected: $name
                               |  Found   : $value (${value.getClass})
                             """.stripMargin))

  val valueOrderReads: Reads[(Option[T], Option[Int])] =
    Reads[(Option[T], Option[Int])] {
      case JsNull => JsSuccess((None, None))
      case j: JsObject =>
        ((j \ "value").validate(format).map(Option(_)).orElse((j \ name).validateOpt(format)) and
          (j \ "order").validateOpt[Int])
          .apply((v, o) => (v, o))
      case j => j.validate(format).map(v => Some(v) -> None)
    }

  def parseValue(value: JsValue): Try[(JsValue, Option[Int])] =
    valueOrderReads
      .reads(value)
      .fold(
        _ => fail(value),
        v => Success(v._1.fold[JsValue](JsNull)(format.writes) -> v._2)
      )

  def getValue(ccf: CustomFieldValue): Option[T]
  def getJsonValue(ccf: CustomFieldValue): JsValue = getValue(ccf).fold[JsValue](JsNull)(format.writes)
  def getValue(traversal: Traversal.V[CustomFieldValue]): Traversal.Domain[T]
  def getJsonValue(traversal: Traversal.V[CustomFieldValue]): Traversal.Domain[JsValue] = getValue(traversal).domainMap(format.writes)
  def filter(traversal: Traversal.V[CustomFieldValue], predicate: P[JsValue]): Traversal.V[CustomFieldValue]
  def setValue(customFieldValue: CustomFieldValue, value: JsValue): Try[CustomFieldValue]
  def updateValue(traversal: Traversal.V[CustomFieldValue], value: JsValue): Try[Traversal.V[CustomFieldValue]]
  override def toString: String = name
}

object CustomFieldString extends CustomFieldType[String] with TraversalOps {
  override val name: String           = "string"
  override val format: Format[String] = implicitly[Format[String]]

  override def getValue(ccf: CustomFieldValue): Option[String] = ccf.stringValue

  override def getValue(traversal: Traversal.V[CustomFieldValue]): Traversal.Domain[String] = traversal.value(_.stringValue).castDomain

  override def filter(traversal: Traversal.V[CustomFieldValue], predicate: P[JsValue]): Traversal.V[CustomFieldValue] =
    Try(traversal.has(_.stringValue, predicate.mapValue(_.as(format)))).getOrElse(traversal.empty)

  def setValue(customFieldValue: CustomFieldValue, value: JsValue): Try[CustomFieldValue] =
    value match {
      case JsString(v) => Success(customFieldValue.copy(stringValue = Some(v)))
      case JsNull      => Success(customFieldValue.copy(stringValue = None))
      case _           => fail(value)
    }

  def updateValue(traversal: Traversal.V[CustomFieldValue], value: JsValue): Try[Traversal.V[CustomFieldValue]] =
    valueOrderReads
      .reads(value)
      .fold(
        _ => fail(value),
        {
          case (value, order) => Success(order.fold(traversal)(o => traversal.update(_.order, Some(o))).update(_.stringValue, value))
        }
      )
}

object CustomFieldBoolean extends CustomFieldType[Boolean] with TraversalOps {
  override val name: String            = "boolean"
  override val format: Format[Boolean] = implicitly[Format[Boolean]]

  override def getValue(ccf: CustomFieldValue): Option[Boolean] = ccf.booleanValue

  override def getValue(traversal: Traversal.V[CustomFieldValue]): Traversal.Domain[Boolean] = traversal.value(_.booleanValue).castDomain

  override def filter(traversal: Traversal.V[CustomFieldValue], predicate: P[JsValue]): Traversal.V[CustomFieldValue] =
    Try(traversal.has(_.booleanValue, predicate.mapValue(_.as(format)))).getOrElse(traversal.empty)

  def setValue(customFieldValue: CustomFieldValue, value: JsValue): Try[CustomFieldValue] =
    value match {
      case JsBoolean(v) => Success(customFieldValue.copy(booleanValue = Some(v)))
      case JsNull       => Success(customFieldValue.copy(booleanValue = None))
      case _            => fail(value)
    }

  def updateValue(traversal: Traversal.V[CustomFieldValue], value: JsValue): Try[Traversal.V[CustomFieldValue]] =
    valueOrderReads
      .reads(value)
      .fold(
        _ => fail(value),
        {
          case (value, order) => Success(order.fold(traversal)(o => traversal.update(_.order, Some(o))).update(_.booleanValue, value))
        }
      )
}

object CustomFieldInteger extends CustomFieldType[Int] with TraversalOps {
  override val name: String        = "integer"
  override val format: Format[Int] = implicitly[Format[Int]]

  override def getValue(ccf: CustomFieldValue): Option[Int] = ccf.integerValue

  override def getValue(traversal: Traversal.V[CustomFieldValue]): Domain[Int] = traversal.value(_.integerValue).castDomain

  override def filter(traversal: Traversal.V[CustomFieldValue], predicate: P[JsValue]): Traversal.V[CustomFieldValue] =
    Try(traversal.has(_.integerValue, predicate.mapValue(_.as(format)))).getOrElse(traversal.empty)

  def setValue(customFieldValue: CustomFieldValue, value: JsValue): Try[CustomFieldValue] =
    value match {
      case JsNumber(v) => Success(customFieldValue.copy(integerValue = Some(v.toInt)))
      case JsNull      => Success(customFieldValue.copy(integerValue = None))
      case _           => fail(value)
    }

  override def updateValue(traversal: Traversal.V[CustomFieldValue], value: JsValue): Try[Traversal.V[CustomFieldValue]] =
    valueOrderReads
      .reads(value)
      .fold(
        _ => fail(value),
        {
          case (value, order) => Success(order.fold(traversal)(o => traversal.update(_.order, Some(o))).update(_.integerValue, value))
        }
      )
}

object CustomFieldFloat extends CustomFieldType[Double] with TraversalOps {
  override val name: String                                    = "float"
  override val format: Format[Double]                          = implicitly[Format[Double]]
  override def getValue(ccf: CustomFieldValue): Option[Double] = ccf.floatValue

  override def getValue(traversal: Traversal.V[CustomFieldValue]): Domain[Double] = traversal.value(_.floatValue).castDomain

  override def filter(traversal: Traversal.V[CustomFieldValue], predicate: P[JsValue]): Traversal.V[CustomFieldValue] =
    Try(traversal.has(_.floatValue, predicate.mapValue(_.as(format)))).getOrElse(traversal.empty)

  def setValue(customFieldValue: CustomFieldValue, value: JsValue): Try[CustomFieldValue] =
    value match {
      case JsNumber(v) => Success(customFieldValue.copy(floatValue = Some(v.toDouble)))
      case JsNull      => Success(customFieldValue.copy(floatValue = None))
      case _           => fail(value)
    }

  override def updateValue(traversal: Traversal.V[CustomFieldValue], value: JsValue): Try[Traversal.V[CustomFieldValue]] =
    valueOrderReads
      .reads(value)
      .fold(
        _ => fail(value),
        {
          case (value, order) => Success(order.fold(traversal)(o => traversal.update(_.order, Some(o))).update(_.floatValue, value))
        }
      )
}

object CustomFieldDate extends CustomFieldType[Date] with TraversalOps {
  override val name: String         = "date"
  override val format: Format[Date] = Format[Date](Reads.LongReads.map(new Date(_)), Writes.LongWrites.contramap(_.getTime))

  override def getValue(ccf: CustomFieldValue): Option[Date] = ccf.dateValue

  override def getValue(traversal: Traversal.V[CustomFieldValue]): Domain[Date] = traversal.value(_.dateValue).castDomain

  override def filter(traversal: Traversal.V[CustomFieldValue], predicate: P[JsValue]): Traversal.V[CustomFieldValue] =
    Try(traversal.has(_.dateValue, predicate.mapValue(_.as(format)))).getOrElse(traversal.empty)

  def setValue(customFieldValue: CustomFieldValue, value: JsValue): Try[CustomFieldValue] =
    value match {
      case JsNumber(v) => Success(customFieldValue.copy(dateValue = Some(new Date(v.toLong))))
      case JsNull      => Success(customFieldValue.copy(dateValue = None))
      case _           => fail(value)
    }

  override def updateValue(traversal: Traversal.V[CustomFieldValue], value: JsValue): Try[Traversal.V[CustomFieldValue]] =
    valueOrderReads
      .reads(value)
      .fold(
        _ => fail(value),
        {
          case (value, order) => Success(order.fold(traversal)(o => traversal.update(_.order, Some(o))).update(_.dateValue, value))
        }
      )
}

@DefineIndex(IndexType.unique, "name")
@BuildVertexEntity
case class CustomField(
    name: String,
    displayName: String,
    description: String,
    `type`: CustomFieldType[_],
    mandatory: Boolean,
    options: Seq[JsValue]
)

case class RichCustomField(customField: CustomField with Entity, customFieldValue: CustomFieldValue with Entity) {
  def _id: EntityId              = customFieldValue._id
  def _createdBy: String         = customFieldValue._createdBy
  def _updatedBy: Option[String] = customFieldValue._updatedBy
  def _createdAt: Date           = customFieldValue._createdAt
  def _updatedAt: Option[Date]   = customFieldValue._updatedAt
  def name: String               = customField.name
  def description: String        = customField.description
  def typeName: String           = customField.`type`.toString
  def value: Option[Any]         = `type`.getValue(customFieldValue)
  def jsValue: JsValue           = `type`.getJsonValue(customFieldValue)
  def order: Option[Int]         = customFieldValue.order
  def `type`: CustomFieldType[_] = customField.`type`
  //def toJson: JsValue            = value.fold[JsValue](JsNull)(`type`.format.asInstanceOf[Format[Any]].writes)
}
