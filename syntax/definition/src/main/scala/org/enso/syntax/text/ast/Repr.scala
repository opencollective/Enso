package org.enso.syntax.text.ast

import org.enso.data.List1
import org.enso.data.Shifted
import cats.Monoid

import scala.annotation.tailrec

//////////////
//// Repr ////
//////////////

sealed trait Repr extends Repr.Provider {
  import Repr._

  val repr = this

  val span: Int

  def +[T: Repr.Of](that: T): Repr =
    Seq(this, implicitly[Repr.Of[T]].of(that))

  def ++[T: Repr.Of](that: T): Repr =
    this + " " + that

  def show(): String = {
    val bldr = new StringBuilder()
    @tailrec
    def go(lst: List[Repr]): Unit = lst match {
      case Nil =>
      case r :: rs =>
        r match {
          case r: Empty =>
            go(rs)
          case r: Letter =>
            bldr += r.char
            go(rs)
          case r: Text =>
            bldr ++= r.str
            go(rs)
          case r: Seq =>
            go(r.first :: r.second :: rs)
        }
    }
    go(List(repr))
    bldr.result()
  }
}

object Repr {

  //// Apply ////

  def apply():                 Repr = Empty()
  def apply[T: Repr.Of](t: T): Repr = implicitly[Repr.Of[T]].of(t)

  //// Constructors ////

  val R = Repr.Empty()
  case class Empty() extends Repr {
    val span = 0
  }

  case class Letter(char: Char) extends Repr {
    val span = 1
  }

  case class Text(str: String) extends Repr {
    val span = str.length
  }

  final case class Seq(first: Repr, second: Repr) extends Repr {
    val span = first.span + second.span
  }

  //// Provider ////

  trait Provider {
    val repr: Repr
  }

  //// Implicits ////

  implicit def fromString(a: String): Repr = Repr(a)
  implicit def fromChar(a: Char):     Repr = Repr(a)

  implicit def _Provider_[T: Repr.Of](t: T): Provider = of(t)

  //// Instances ////

  implicit val monoid: Monoid[Repr] = new Monoid[Repr] {
    def empty:                     Repr = R
    def combine(l: Repr, r: Repr): Repr = l + r
  }

  /////////////////////////////
  ///// Repr.Of Type Class ////
  /////////////////////////////

  trait Of[-T] {
    def of(a: T): Repr
  }
  def of[T](t: T)(implicit ev: Repr.Of[T]) = ev.of(t)

  ///// Instances ////

  implicit def _inst_0: Repr.Of[Unit]   = _ => Repr.Empty()
  implicit def _inst_1: Repr.Of[String] = Repr.Text(_)
  implicit def _inst_2: Repr.Of[Int]    = i => Repr.Text(" " * i)
  implicit def _inst_3: Repr.Of[Char]   = Repr.Letter(_)
  implicit def _inst_4: Repr.Of[Repr]   = identity(_)

  implicit def _inst_5[T1: Repr.Of, T2: Repr.Of]: Repr.Of[(T1, T2)] = {
    case (t1, t2) => Repr.Seq(Repr.of(t1), Repr.of(t2))
  }

  implicit def _inst_6[T <: Repr.Provider]: Repr.Of[T] = _.repr

  implicit def _inst_7[T: Repr.Of]: Repr.Of[List[T]] =
    _.foldLeft(Repr.Empty(): Repr)((a, b) => Repr.Seq(a, Repr.of(b)))

  implicit def _inst_8[T: Repr.Of]: Repr.Of[List1[T]] =
    _.foldLeft(Repr.Empty(): Repr)((a, b) => Repr.Seq(a, Repr.of(b)))

  implicit def _inst_9[T: Repr.Of]: Repr.Of[Shifted[T]] =
    t => R + t.off + t.el

  implicit def _inst_10[T: Repr.Of]: Repr.Of[Shifted.List1[T]] =
    t => R + t.head + t.tail

  implicit def _inst_11[T: Repr.Of]: Repr.Of[Option[T]] =
    _.map(Repr.of(_)).getOrElse(R)

  implicit def _inst_12: Repr.Of[None.type] =
    _ => R

  implicit def _inst_13[T: Repr.Of]: Repr.Of[Some[T]] =
    _.map(Repr.of(_)).getOrElse(R)
}
