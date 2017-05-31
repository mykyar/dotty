package dotty.tools.dotc
package transform.localopt

import core.Constants.{Constant, NullTag}
import core.Contexts.Context
import core.Symbols._
import core.Types._
import core.Flags._
import ast.Trees._
import scala.collection.mutable

/** Eliminated null checks based on the following observations:
 *
 *  - (this)  cannot be null
 *  - (new C) cannot be null
 *  - literal is either null itself or non null
 *  - fallsback to `tpe.isNotNull`, which will eventually be true for non nullable types.
 *  - in (a.call; a == null), the first call throws a NPE if a is null; the test can be removed.
 */
 class RemoveUnnecessaryNullChecks(implicit val ctx: Context) extends Optimisation {
  import ast.tpd._

  val initializedVals = mutable.HashSet[Symbol]()

  val checkGood = mutable.HashMap[Symbol, Set[Symbol]]()

  def isGood(t: Symbol) = {
    t.exists && initializedVals.contains(t) && {
      var changed = true
      var set = Set(t)
      while (changed) {
        val oldSet = set
        set = set ++ set.flatMap(x => checkGood.getOrElse(x, Nil))
        changed = set != oldSet
      }
      !set.exists(x => !initializedVals.contains(x))
    }
  }

  val visitor: Tree => Unit = {
    case vd: ValDef =>
      val rhs = vd.rhs
      val rhsName = rhs.symbol.name
      if (!vd.symbol.is(Mutable) && !rhs.isEmpty) {
        def checkNonNull(t: Tree, target: Symbol): Boolean = t match {
          case Block(_ , expr) => checkNonNull(expr, target)
          case If(_, thenp, elsep) => checkNonNull(thenp, target) && checkNonNull(elsep, target)
          case t: New => true
          case t: Apply if t.symbol.isPrimaryConstructor => true
          case t: Literal => t.const.value != null
          case t: This => true
          case t: Ident if !t.symbol.owner.isClass =>
            checkGood.put(target, checkGood.getOrElse(target, Set.empty) + t.symbol)
            true
          case t: Apply if !t.symbol.owner.isClass =>
            checkGood.put(target, checkGood.getOrElse(target, Set.empty) + t.symbol)
            true
          case t: Typed =>
            checkNonNull(t.expr, target)
          case _ => t.tpe.isNotNull
        }
        if (checkNonNull(vd.rhs, vd.symbol))
          initializedVals += vd.symbol
      }
    case t: Tree =>
  }


  def transformer(localCtx: Context): Tree => Tree = {
    implicit val ctx: Context = localCtx
    def isNullLiteral(tree: Tree) = tree match {
      case literal: Literal =>
        literal.const.tag == NullTag
      case _ => false
    }
    val transformation: Tree => Tree = {
      case check@Apply(Select(lhs, _), List(rhs)) =>
        val sym = check.symbol
        if ( ((sym == defn.Object_eq) || (sym == defn.Object_ne)) &&
          ((isNullLiteral(lhs) && isGood(rhs.symbol)) || (isNullLiteral(rhs) && isGood(lhs.symbol)))) {
          if (sym == defn.Object_eq) Block(List(lhs, rhs), Literal(Constant(false)))
          else if(sym == defn.Object_ne) Block(List(lhs, rhs), Literal(Constant(true)))
          else check
        } else check
      case t => t
    }
    transformation
  }
}
