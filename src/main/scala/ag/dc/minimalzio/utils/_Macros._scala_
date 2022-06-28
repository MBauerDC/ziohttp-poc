package ag.dc.minimalzio.utils

import scala.quoted.*
import zhttp.http.Request
import zhttp.http.Response
import zhttp.http.Method
import zhttp.http.Path

object Macros:
  def createPathImpl(method: Method, parts: List[Expr[String]])(using Quotes): Expr[PartialFunction[Request, Response]] = 
    type StrVar = String
    given ToExpr[Method] with
      def toExpr(m: Method) =
        '{ ${m.toString()} }
    val partOrVarList: List[Either[String, StrVar]] = for {
      strExpr <- parts
      strVal = strExpr.valueOrAbort
      partOrVar = if (strVal.startsWith(":")) then Right(strVal.substring(1).asInstanceOf[StrVar]) else Left(strVal)
    } yield partOrVar
    def makePartOrVarExpr(v: Either[String, StrVar]): Expr[String] = 
        v.fold(l => Expr('"' + l + '"'), r => Expr(r))
    val mapped = partOrVarList.map(makePartOrVarExpr)
    val method: Expr[Method] = Expr(Method.GET)
    val pathR = {
        case '{ $method -> !! / "path"} => Response.text("a")
    }
    val folded: Expr[(Method, Path)] = mapped.fold(Expr(method.toString + " ->" + " !! / "))((a1, a2) => Expr(a1.valueOrAbort + " / " + a2.valueOrAbort))
    val a:PartialFunction[Request, Response] = '{ case Some(a) => a}
    val pfR: Expr[PartialFunction[Request, Response]] = '{ case ${folded} => Response.text("abc") }
