import scala.reflect.runtime.{ universe => ru }
import org.mashupbots.socko.rest._

val rm = ru.runtimeMirror(getClass().getClassLoader())

object EntryStatus extends Enumeration {
  type EntryStatus = Value
  val Creating, Loading, Created, Remote = Value
}

object En extends Enumeration {
  type En = Value; val En1, En2, En3 = Value
}

class MyClass {
  def myMethod(e: EntryStatus.EntryStatus, f: En.En) = e.toString + f.toString
}

val e = EntryStatus.Loading
//val tpe = rm.classSymbol(e.getClass).toType
//tpe.asInstanceOf[ru.TypeRef].pre.members.view.filter(_.isTerm).filterNot(_.isMethod).filterNot(_.isModule).filterNot(_.isClass).toList

val tpe = ru.typeOf[MyClass].declaration(ru.newTermName("myMethod")).asMethod.paramss(0).map(_.asTerm).head.typeSignature



tpe.asInstanceOf[ru.TypeRef].pre.members.view.filter(_.isTerm).filterNot(_.isMethod).filterNot(_.isMethod).filterNot(_.isClass).toList.head
tpe <:< ru.typeOf[Enumeration]
tpe.baseClasses
ru.typeOf[EntryStatus.EntryStatus]
tpe.baseClasses.head.fullName
tpe.members.head.typeSignature

tpe.members.head.typeSignature.find(t => t <:< ru.typeOf[Enumeration])