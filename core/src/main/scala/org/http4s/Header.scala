/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Based on https://github.com/spray/spray/blob/v1.1-M7/spray-http/src/main/scala/spray/http/HttpHeader.scala
 * Copyright (C) 2011-2012 spray.io
 * Based on code copyright (C) 2010-2011 by the BlueEyes Web Framework Team
 */

package org.http4s

import cats.{Eq, Order, Show}
import cats.data.NonEmptyList
import cats.syntax.all._
import org.http4s.internal.parboiled2.CharPredicate
import org.http4s.internal.sanitize
import org.http4s.syntax.string._
import org.http4s.util._
import scala.util.hashing.MurmurHash3

/** Abstract representation o the HTTP header
  * @see org.http4s.HeaderKey
  */
sealed trait Header extends Renderable with Product {
  import Header.Raw

  def name: CaseInsensitiveString

  /** True if [[name]] is a valid field-name per RFC7230.  Where it
    * is not, the header may be dropped by the backend.
    */
  def isNameValid: Boolean = true

  def parsed: Header

  def renderValue(writer: Writer): writer.type

  def value: String = {
    val w = new StringWriter
    renderValue(w).result
  }

  def is(key: HeaderKey): Boolean = key.matchHeader(this).isDefined

  def isNot(key: HeaderKey): Boolean = !is(key)

  override def toString: String = name.toString + ": " + value

  def toRaw: Raw = Raw(name, value)

  final def render(writer: Writer): writer.type = {
    writer << name << ':' << ' '
    renderValue(writer)
  }

  final override def hashCode(): Int =
    MurmurHash3.mixLast(name.hashCode, MurmurHash3.productHash(parsed))

  final override def equals(that: Any): Boolean =
    that match {
      case h: AnyRef if this eq h => true
      case h: Header =>
        (name == h.name) &&
          (parsed.productArity == h.parsed.productArity) &&
          (parsed.productIterator.sameElements(h.parsed.productIterator))
      case _ => false
    }

  /** Length of the rendered header, including name and final '\r\n' */
  def renderedLength: Long =
    render(new HeaderLengthCountingWriter).length + 2
}

object Header {
  def unapply(header: Header): Option[(CaseInsensitiveString, String)] =
    Some((header.name, header.value))

  def apply(name: String, value: String): Raw = Raw(name.ci, value)

  private val FieldNamePredicate =
    CharPredicate("!#$%&'*+-.^_`|~`") ++ CharPredicate.AlphaNum
  private val FieldValueInvalidPredicate =
    CharPredicate(0x0.toChar, '\r', '\n')

  /** Raw representation of the Header
    *
    * This can be considered the simplest representation where the header is specified as the product of
    * a key and a value
    * @param name case-insensitive string used to identify the header
    * @param value String representation of the header value
    */
  final case class Raw(name: CaseInsensitiveString, override val value: String) extends Header {
    private[this] var _parsed: Header = null
    final override def parsed: Header = {
      if (_parsed == null)
        _parsed = parser.HttpHeaderParser.parseHeader(this).getOrElse(this)
      _parsed
    }
    override def renderValue(writer: Writer): writer.type =
      writer.append(sanitize(value, FieldValueInvalidPredicate, ' '))

    override lazy val isNameValid: Boolean =
      name.toString.nonEmpty && name.toString.forall(FieldNamePredicate)
  }

  /** A Header that is already parsed from its String representation. */
  trait Parsed extends Header {
    def key: HeaderKey
    def name: CaseInsensitiveString = key.name
    def parsed: this.type = this
  }

  /** A recurring header that satisfies this clause of the Spec:
    *
    * Multiple message-header fields with the same field-name MAY be present in a message if and only if the entire
    * field-value for that header field is defined as a comma-separated list [i.e., #(values)]. It MUST be possible
    * to combine the multiple header fields into one "field-name: field-value" pair, without changing the semantics
    * of the message, by appending each subsequent field-value to the first, each separated by a comma.
    */
  trait Recurring extends Parsed {
    type Value
    def values: NonEmptyList[Value]
  }

  /** Simple helper trait that provides a default way of rendering the value */
  trait RecurringRenderable extends Recurring {
    type Value <: Renderable
    override def renderValue(writer: Writer): writer.type = {
      values.head.render(writer)
      values.tail.foreach(writer << ", " << _)
      writer
    }
  }

  implicit val HeaderShow: Show[Header] = Show.show[Header] {
    _.toString
  }

  @deprecated(message = "Please use HeaderOrder instead", since = "0.21.12")
  def HeaderEq: Eq[Header] = HeaderOrder

  implicit lazy val HeaderOrder: Order[Header] =
    Order.from { case (a, b) =>
      val nameComparison: Int = a.name.compare(b.name)
      if (nameComparison === 0) {
        a.value.compare(b.value)
      } else {
        nameComparison
      }
    }
}
