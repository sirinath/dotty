package dotty.tools
package dotc
package typer

import core._
import ast.{Trees, untpd, tpd, TreeInfo}
import util.Positions._
import Trees.Untyped
import Mode.ImplicitsDisabled
import Contexts._
import Flags._
import Denotations._
import NameOps._
import Symbols._
import Types._
import Decorators._
import Names._
import StdNames._
import Constants._
import Inferencing._
import EtaExpansion._
import collection.mutable
import language.implicitConversions

object Applications {

  private val isNamedArg = (arg: Any) => arg.isInstanceOf[Trees.NamedArg[_]]
  def hasNamedArg(args: List[Any]) = args exists isNamedArg

  /** A trait defining an `isCompatible` method. */
  trait Compatibility {

    /** Is there an implicit conversion from `tp` to `pt`? */
    def viewExists(tp: Type, pt: Type)(implicit ctx: Context): Boolean

    /** A type `tp` is compatible with a type `pt` if one of the following holds:
     *    1. `tp` is a subtype of `pt`
     *    2. `pt` is by name parameter type, and `tp` is compatible with its underlying type
     *    3. there is an implicit conversion from `tp` to `pt`.
     */
    def isCompatible(tp: Type, pt: Type)(implicit ctx: Context): Boolean = (
       tp <:< pt
    || pt.typeSymbol == defn.ByNameParamClass && tp <:< pt.typeArgs.head
    || viewExists(tp, pt)
    )
  }

  /** The normalized form of a type
   *   - unwraps polymorphic types, tracking their parameters in the current constraint
   *   - skips implicit parameters
   *   - converts non-dependent method types to the corresponding function types
   *   - dereferences parameterless method types
   */
  def normalize(tp: Type)(implicit ctx: Context): Type = tp.widen match {
    case pt: PolyType => normalize(ctx.track(pt).resultType)
    case mt: MethodType if !mt.isDependent =>
      if (mt.isImplicit) mt.resultType
      else defn.FunctionType(mt.paramTypes, mt.resultType)
    case et: ExprType => et.resultType
    case _ => tp
  }
}

import Applications._

trait Applications extends Compatibility{ self: Typer =>

  import Applications._
  import Trees._

  private def state(implicit ctx: Context) = ctx.typerState

  /**
   *  @param Arg        the type of arguments, could be tpd.Tree, untpd.Tree, or Type
   *  @param methRef    the reference to the method of the application
   *  @param funType    the type of the function part of the application
   *  @param args       the arguments of the application
   *  @param resultType the expected result type of the application
   */
  abstract class Application[Arg](methRef: TermRef, funType: Type, args: List[Arg], resultType: Type)(implicit ctx: Context) {

    /** The type of typed arguments: either tpd.Tree or Type */
    type TypedArg

    /** Given an original argument and the type of the corresponding formal
     *  parameter, produce a typed argument.
     */
    protected def typedArg(arg: Arg, formal: Type): TypedArg

    /** Turn a typed tree into an argument */
    protected def treeToArg(arg: tpd.Tree): Arg

    /** Check that argument corresponds to type `formal` and
     *  possibly add it to the list of adapted arguments
     */
    protected def addArg(arg: TypedArg, formal: Type): Unit

    /** Is this an argument of the form `expr: _*` or a RepeatedParamType
     *  derived from such an argument?
     */
    protected def isVarArg(arg: Arg): Boolean

    /** If constructing trees, turn last `n` processed arguments into a
     *  `SeqLiteral` tree with element type `elemFormal`.
     */
    protected def makeVarArg(n: Int, elemFormal: Type): Unit

    /** Signal failure with given message at position of given argument */
    protected def fail(msg: => String, arg: Arg): Unit

    /** Signal failure with given message at position of the application itself */
    protected def fail(msg: => String): Unit

    /** If constructing trees, the current function part, which might be
     *  affected by lifting. EmptyTree otherwise.
     */
    protected def normalizedFun: tpd.Tree

    /** If constructing trees, pull out all parts of the function
     *  which are not idempotent into separate prefix definitions
     */
    protected def liftFun(): Unit = ()

    /** A flag signalling that the application was so far succesful */
    protected var ok = true

    /** The function's type after widening and instantiating polytypes
     *  with polyparams or typevars in constraint set
     */
    val methType = funType.widen match {
      case funType: MethodType => funType
      case funType: PolyType => ctx.track(funType).resultType
      case _ => funType
    }

    /** The arguments re-ordered so that each named argument matches the
     *  same-named formal parameter.
     */
    val orderedArgs =
      if (hasNamedArg(args))
        reorder(args.asInstanceOf[List[untpd.Tree]]).asInstanceOf[List[Arg]]
      else
        args

    methType match {
      case methType: MethodType =>
        // apply the result type constraint, unless method type is dependent
        if (!methType.isDependent)
          ok = ok && constrainResult(methType.resultType, resultType)
        // match all arguments with corresponding formal parameters
        matchArgs(orderedArgs, methType.paramTypes, 0)
      case _ =>
        if (methType.isError) ok = false
        else fail(s"$methString does not take parameters")
    }

    /** The application was succesful */
    def success = ok

    private def state = ctx.typerState

    protected def methodType = methType.asInstanceOf[MethodType]
    private def methString: String = s"method ${methRef.name}: ${methType.show}"

    /** Re-order arguments to correctly align named arguments */
    def reorder[T >: Untyped](args: List[Tree[T]]): List[Tree[T]] = {
      var namedToArg: Map[Name, Tree[T]] =
        (for (NamedArg(name, arg1) <- args) yield (name, arg1)).toMap

      def badNamedArg(arg: Tree[_ >: Untyped]): Unit = {
        val NamedArg(name, _) = arg
        def msg =
          if (methodType.paramNames contains name)
            s"parameter $name of $methString is already instantiated"
          else
            s"$methString does not have a parameter $name"
        fail(msg, arg.asInstanceOf[Arg])
      }

      def recur(pnames: List[Name], args: List[Tree[T]]): List[Tree[T]] = pnames match {
        case pname :: pnames1 =>
          namedToArg get pname match {
            case Some(arg) =>
              namedToArg -= pname
              arg :: recur(pnames1, args)
            case None =>
              args match {
                case (arg @ NamedArg(aname, _)) :: args1 =>
                  if (namedToArg contains aname)
                    emptyTree[T]() :: recur(pnames1, args)
                  else {
                    badNamedArg(arg)
                    recur(pnames1, args1)
                  }
                case arg :: args1 =>
                  arg :: recur(pnames1, args1)
                case Nil =>
                  recur(pnames1, args)
              }
          }
        case nil =>
          if (hasNamedArg(args)) {
            val (namedArgs, otherArgs) = args partition isNamedArg
            namedArgs foreach badNamedArg
            otherArgs
          }
          else args
      }

      recur(methodType.paramNames, args)
    }

    /** Splice new method reference into existing application */
    def spliceMeth(meth: tpd.Tree, app: tpd.Tree): tpd.Tree = app match {
      case Apply(fn, args) => tpd.Apply(spliceMeth(meth, fn), args)
      case TypeApply(fn, targs) => tpd.TypeApply(spliceMeth(meth, fn), targs)
      case _ => meth
    }

    /** Find reference to default parameter getter for parameter #n in current
     *  parameter list, or NoType if none was found
     */
    def findDefaultGetter(n: Int)(implicit ctx: Context): Type = {
      def getterName = methRef.name.toTermName.defaultGetterName(n)
      def ref(pre: Type, sym: Symbol): Type =
        if (pre.exists && sym.isTerm) TermRef.withSym(pre, sym.asTerm) else NoType
      val meth = methRef.symbol
      if (meth.hasDefaultParams)
        methRef.prefix match {
          case NoPrefix =>
            def findDefault(cx: Context): Type = {
              if (cx eq NoContext) NoType
              else if (cx.scope != cx.outer.scope &&
                       cx.lookup(methRef.name)
                         .filterWithPredicate(_.symbol == meth).exists) {
                val denot = cx.lookup(getterName).toDenot(NoPrefix)
                NamedType(NoPrefix, getterName).withDenot(denot)
              } else findDefault(cx.outer)
            }
            findDefault(ctx)
          case mpre =>
            val cls = meth.owner
            val pre =
              if (meth.isClassConstructor) {
                mpre.baseType(cls) match {
                  case TypeRef(clspre, _) => ref(clspre, cls.companionModule)
                  case _ => NoType
                }
              } else mpre
            ref(pre, pre.member(getterName).symbol)
        }
      else NoType
    }

    /** Match re-ordered arguments against formal parameters
     *  @param n   The position of the first parameter in formals in `methType`.
     */
    def matchArgs(args: List[Arg], formals: List[Type], n: Int): Unit = {
      if (success) formals match {
        case formal :: formals1 =>

          def addTyped(arg: Arg, formal: Type) =
            addArg(typedArg(arg, formal), formal)

          def missingArg(n: Int): Unit = {
            val pname = methodType.paramNames(n)
            fail(
              if (pname contains '$') s"not enough arguments for $methString"
              else s"missing argument for parameter $pname of $methString")
          }

          def tryDefault(n: Int, args1: List[Arg]): Unit = {
            findDefaultGetter(n + TreeInfo.numArgs(normalizedFun)) match {
              case dref: NamedType =>
                liftFun()
                addTyped(treeToArg(spliceMeth(tpd.Ident(dref), normalizedFun)), formal)
                matchArgs(args1, formals1, n + 1)
              case _ =>
                missingArg(n)
              }
          }

          if (formal.isRepeatedParam)
            args match {
              case arg :: Nil if isVarArg(arg) =>
                addTyped(arg, formal)
              case _ =>
                val elemFormal = formal.typeArgs.head
                args foreach (addTyped(_, elemFormal))
                makeVarArg(args.length, elemFormal)
            }
          else args match {
            case EmptyTree :: args1 =>
              tryDefault(n, args1)
            case arg :: args1 =>
              addTyped(arg, formal)
              matchArgs(args1, formals1, n + 1)
            case nil =>
              tryDefault(n, args)
          }

        case nil =>
          args match {
            case arg :: args1 => fail(s"too many arguments for $methString", arg)
            case nil =>
          }
      }
    }

    /** Take into account that the result type of the current method
     *  must fit the given expected result type.
     */
    def constrainResult(mt: Type, pt: Type): Boolean = pt match {
      case FunProtoType(_, result) =>
        mt match {
          case mt: MethodType if !mt.isDependent =>
            constrainResult(mt.resultType, pt.resultType)
          case _ =>
            true
        }
      case pt: ValueType =>
        mt match {
          case mt: ImplicitMethodType if !mt.isDependent =>
            constrainResult(mt.resultType, pt)
          case _ =>
            isCompatible(mt, pt)
        }
      case _ =>
        true
    }
  }

  /** Subclass of Application for the cases where we are interested only
   *  in a "can/cannot apply" answer, without needing to construct trees or
   *  issue error messages.
   */
  abstract class TestApplication[Arg](methRef: TermRef, funType: Type, args: List[Arg], resultType: Type)(implicit ctx: Context)
  extends Application[Arg](methRef, funType, args, resultType) {
    type TypedArg = Arg
    type Result = Unit

    /** The type of the given argument */
    protected def argType(arg: Arg): Type

    def typedArg(arg: Arg, formal: Type): Arg = arg
    def addArg(arg: TypedArg, formal: Type) =
      ok = ok & isCompatible(argType(arg), formal)
    def makeVarArg(n: Int, elemFormal: Type) = {}
    def fail(msg: => String, arg: Arg) =
      ok = false
    def fail(msg: => String) =
      ok = false
    def normalizedFun = tpd.EmptyTree
  }

  /** Subtrait of Application for the cases where arguments are (typed or
   *  untyped) trees.
   */
  trait TreeApplication[T >: Untyped] extends Application[Tree[T]] {
    type TypeArg = tpd.Tree
    def isVarArg(arg: Tree[T]): Boolean = TreeInfo.isWildcardStarArg(arg)
  }

  /** Subclass of Application for applicability tests with trees as arguments. */
  class ApplicableToTrees(methRef: TermRef, args: List[tpd.Tree], resultType: Type)(implicit ctx: Context)
  extends TestApplication(methRef, methRef, args, resultType) with TreeApplication[Type] {
    def argType(arg: tpd.Tree): Type = normalize(arg.tpe)
    def treeToArg(arg: tpd.Tree): tpd.Tree = arg
  }

  /** Subclass of Application for applicability tests with types as arguments. */
  class ApplicableToTypes(methRef: TermRef, args: List[Type], resultType: Type)(implicit ctx: Context)
  extends TestApplication(methRef, methRef, args, resultType) {
    def argType(arg: Type): Type = arg
    def treeToArg(arg: tpd.Tree): Type = arg.tpe
    def isVarArg(arg: Type): Boolean = arg.isRepeatedParam
  }

  /** Subclass of Application for type checking an Apply node, where
   *  types of arguments are either known or unknown.
   */
  abstract class TypedApply[T >: Untyped](
    app: untpd.Apply, fun: tpd.Tree, methRef: TermRef, args: List[Tree[T]], resultType: Type)(implicit ctx: Context)
  extends Application(methRef, fun.tpe, args, resultType) with TreeApplication[T] {
    type TypedArg = tpd.Tree
    private var typedArgBuf = new mutable.ListBuffer[tpd.Tree]
    private var liftedDefs: mutable.ListBuffer[tpd.Tree] = null
    private var myNormalizedFun: tpd.Tree = fun

    def addArg(arg: tpd.Tree, formal: Type): Unit =
      typedArgBuf += adapt(arg, formal)

    def makeVarArg(n: Int, elemFormal: Type): Unit = {
      val args = typedArgBuf.takeRight(n).toList
      typedArgBuf.trimEnd(n)
      val seqType = if (methodType.isJava) defn.ArrayType else defn.SeqType
      typedArgBuf += tpd.SeqLiteral(seqType.appliedTo(elemFormal :: Nil), args)
    }

    def fail(msg: => String, arg: Tree[T]) = {
      ctx.error(msg, arg.pos)
      ok = false
    }

    def fail(msg: => String) = {
      ctx.error(msg, app.pos)
      ok = false
    }

    def normalizedFun = myNormalizedFun

    override def liftFun(): Unit =
      if (liftedDefs == null) {
        liftedDefs = new mutable.ListBuffer[tpd.Tree]
        myNormalizedFun = liftApp(liftedDefs, myNormalizedFun)
      }

    /** The index of the first difference between lists of trees `xs` and `ys`,
     *  where `EmptyTree`s in the second list are skipped.
     *  -1 if there are no differences.
     */
    private def firstDiff[T <: Tree[_]](xs: List[T], ys: List[T], n: Int = 0): Int = xs match {
      case x :: xs1 =>
        ys match {
          case EmptyTree :: ys1 => firstDiff(xs1, ys1, n)
          case y :: ys1 => if (x ne y) n else firstDiff(xs1, ys1, n + 1)
          case nil => n
        }
      case nil =>
        ys match {
          case EmptyTree :: ys1 => firstDiff(xs, ys1, n)
          case y :: ys1 => n
          case nil => -1
        }
    }
    def sameSeq[T <: Tree[_]](xs: List[T], ys: List[T]): Boolean = firstDiff(xs, ys) < 0

    val result: tpd.Tree =
      if (!success) app withType ErrorType
      else {
        var typedArgs = typedArgBuf.toList
        if (!sameSeq(app.args, orderedArgs)) {
          // need to lift arguments to maintain evaluation order in the
          // presence of argument reorderings.
          liftFun()
          val eqSuffixLength = firstDiff(app.args.reverse, orderedArgs.reverse)
          val (liftable, rest) = typedArgs splitAt (typedArgs.length - eqSuffixLength)
          typedArgs = liftArgs(liftedDefs, methType, liftable) ++ rest
        }
        if (sameSeq(typedArgs, args)) // trick to cut down on tree copying
          typedArgs = args.asInstanceOf[List[tpd.Tree]]
        val app1 = app.withType(methodType.instantiate(typedArgs map (_.tpe)))
          .derivedApply(normalizedFun, typedArgs)
        if (liftedDefs != null && liftedDefs.nonEmpty) tpd.Block(liftedDefs.toList, app1)
        else app1
      }
  }

  /** Subclass of Application for type checking an Apply node with untyped arguments. */
  class ApplyToUntyped(app: untpd.Apply, fun: tpd.Tree, methRef: TermRef, args: List[untpd.Tree], resultType: Type)(implicit ctx: Context)
  extends TypedApply(app, fun, methRef, args, resultType) {
    def typedArg(arg: untpd.Tree, formal: Type): TypedArg = typed(arg, formal)
    def treeToArg(arg: tpd.Tree): untpd.Tree = untpd.TypedSplice(arg)
  }

  /** Subclass of Application for type checking an Apply node with typed arguments. */
  class ApplyToTyped(app: untpd.Apply, fun: tpd.Tree, methRef: TermRef, args: List[tpd.Tree], resultType: Type)(implicit ctx: Context)
  extends TypedApply(app, fun, methRef, args, resultType) {
    def typedArg(arg: tpd.Tree, formal: Type): TypedArg = arg
    def treeToArg(arg: tpd.Tree): tpd.Tree = arg
  }

  def typedApply(app: untpd.Apply, fun: tpd.Tree, methRef: TermRef, args: List[tpd.Tree], resultType: Type)(implicit ctx: Context): tpd.Tree =
    new ApplyToTyped(app, fun, methRef, args, resultType).result

  def typedApply(fun: tpd.Tree, methRef: TermRef, args: List[tpd.Tree], resultType: Type)(implicit ctx: Context): tpd.Tree =
    typedApply(Apply(untpd.TypedSplice(fun), Nil), fun, methRef, args, resultType)

  /** Is given method reference applicable to argument types `args`?
   *  @param  resultType   The expected result type of the application
   */
  def isApplicableToTrees(methRef: TermRef, args: List[tpd.Tree], resultType: Type)(implicit ctx: Context) =
    new ApplicableToTrees(methRef, args, resultType)(ctx.fresh.withNewTyperState).success

  /** Is given method reference applicable to arguments `args`?
   *  @param  resultType   The expected result type of the application
   */
  def isApplicableToTypes(methRef: TermRef, args: List[Type], resultType: Type = WildcardType)(implicit ctx: Context) =
    new ApplicableToTypes(methRef, args, resultType)(ctx.fresh.withNewTyperState).success

  /** Is `tp` a subtype of `pt`? */
  def testCompatible(tp: Type, pt: Type)(implicit ctx: Context) =
    isCompatible(tp, pt)(ctx.fresh.withNewTyperState)

  /** In a set of overloaded applicable alternatives, is `alt1` at least as good as
   *  `alt2`? `alt1` and `alt2` are nonoverloaded references.
   */
  def isAsGood(alt1: TermRef, alt2: TermRef)(implicit ctx: Context): Boolean = {

    /** Is class or module class `sym1` derived from class or module class `sym2`? */
    def isDerived(sym1: Symbol, sym2: Symbol): Boolean =
      if (sym1 isSubClass sym2) true
      else if (sym2 is Module) isDerived(sym1, sym2.companionClass)
      else (sym1 is Module) && isDerived(sym1.companionClass, sym2)

    /** Is alternative `alt1` with type `tp1` as specific as alternative
     *  `alt2` with type `tp2` ? This is the case if `tp2` can be applied to
     *  `tp1` (without intervention of implicits) or `tp2' is a supertype of `tp1`.
     */
    def isAsSpecific(alt1: TermRef, tp1: Type, alt2: TermRef, tp2: Type): Boolean = tp1 match {
      case tp1: PolyType =>
        def bounds(tparamRefs: List[TypeRef]) = tp1.paramBounds map (_.substParams(tp1, tparamRefs))
        val tparams = ctx.newTypeParams(alt1.symbol.owner, tp1.paramNames, EmptyFlags, bounds)
        isAsSpecific(alt1, tp1.instantiate(tparams map (_.symRef)), alt2, tp2)
      case tp1: MethodType =>
        isApplicableToTypes(alt2, tp1.paramTypes)(ctx)
      case _ =>
        testCompatible(tp1, tp2)(ctx)
    }

    val owner1 = alt1.symbol.owner
    val owner2 = alt2.symbol.owner
    val tp1 = alt1.widen
    val tp2 = alt2.widen

    def winsOwner1 = isDerived(owner1, owner2)
    def winsType1  = isAsSpecific(alt1, tp1, alt2, tp2)
    def winsOwner2 = isDerived(owner2, owner1)
    def winsType2  = isAsSpecific(alt2, tp2, alt1, tp1)

    // Assume the following probabilities:
    //
    // P(winsOwnerX) = 2/3
    // P(winsTypeX) = 1/3
    //
    // Then the call probabilities of the 4 basic operations are as follows:
    //
    // winsOwner1: 1/1
    // winsOwner2: 1/1
    // winsType1 : 7/9
    // winsType2 : 4/9

    if (winsOwner1) /* 6/9 */ !winsOwner2 || /* 4/9 */ winsType1 || /* 8/27 */ !winsType2
    else if (winsOwner2) /* 2/9 */ winsType1 && /* 2/27 */ !winsType2
    else /* 1/9 */ winsType1 || /* 2/27 */ !winsType2
  }

  def narrowMostSpecific(alts: List[TermRef])(implicit ctx: Context): List[TermRef] = (alts: @unchecked) match {
    case alt :: alts1 =>
      def winner(bestSoFar: TermRef, alts: List[TermRef]): TermRef = alts match {
        case alt :: alts1 =>
          winner(if (isAsGood(alt, bestSoFar)) alt else bestSoFar, alts1)
        case nil =>
          bestSoFar
      }
      val best = winner(alt, alts1)
      def asGood(alts: List[TermRef]): List[TermRef] = alts match {
        case alt :: alts1 =>
          if ((alt eq best) || !isAsGood(alt, best)) asGood(alts1)
          else alt :: asGood(alts1)
        case nil =>
          Nil
      }
      best :: asGood(alts1)
  }

  private val dummyTree = Literal(Constant(null))
  def dummyTreeOfType(tp: Type): tpd.Tree = dummyTree withType tp

  /** Resolve overloaded alternative `alts`, given expected type `pt`. */
  def resolveOverloaded(alts: List[TermRef], pt: Type)(implicit ctx: Context): List[TermRef] = {

    def isDetermined(alts: List[TermRef]) = alts.isEmpty || alts.tail.isEmpty

    /** The shape of given tree as a type; cannot handle named arguments. */
    def typeShape(tree: untpd.Tree): Type = tree match {
      case untpd.Function(args, body) =>
        defn.FunctionType(args map Function.const(defn.AnyType), typeShape(body))
      case _ =>
        defn.NothingType
    }

    /** The shape of given tree as a type; is more expensive than
     *  typeShape but can can handle named arguments.
     */
    def treeShape(tree: untpd.Tree): tpd.Tree = tree match {
      case NamedArg(name, arg) =>
        val argShape = treeShape(arg)
        tree.withType(argShape.tpe).derivedNamedArg(name, argShape)
      case _ =>
        dummyTreeOfType(typeShape(tree))
    }

    def narrowByTypes(alts: List[TermRef], argTypes: List[Type], resultType: Type): List[TermRef] =
      alts filter (isApplicableToTypes(_, argTypes, resultType))

    val candidates = pt match {
      case pt @ FunProtoType(args, resultType) =>
        val numArgs = args.length

        def sizeFits(alt: TermRef, tp: Type): Boolean = tp match {
          case tp: PolyType => sizeFits(alt, tp.resultType)
          case MethodType(_, ptypes) =>
            val numParams = ptypes.length
            def isVarArgs = ptypes.nonEmpty && ptypes.last.isRepeatedParam
            def hasDefault = alt.symbol.hasDefaultParams
            if (numParams == numArgs) true
            else if (numParams < numArgs) isVarArgs
            else if (numParams > numArgs + 1) hasDefault
            else isVarArgs || hasDefault
        }

        def narrowBySize(alts: List[TermRef]): List[TermRef] =
          alts filter (alt => sizeFits(alt, alt.widen))

        def narrowByShapes(alts: List[TermRef]): List[TermRef] =
          if (args exists (_.isInstanceOf[untpd.Function]))
            if (args exists (_.isInstanceOf[NamedArg[_]]))
              narrowByTrees(alts, args map treeShape, resultType)
            else
              narrowByTypes(alts, args map typeShape, resultType)
          else
            alts

        def narrowByTrees(alts: List[TermRef], args: List[tpd.Tree], resultType: Type): List[TermRef] =
          alts filter (isApplicableToTrees(_, args, resultType))

        val alts1 = narrowBySize(alts)
        if (isDetermined(alts1)) alts1
        else {
          val alts2 = narrowByShapes(alts1)
          if (isDetermined(alts2)) alts2
          else narrowByTrees(alts2, pt.typedArgs, resultType)
        }

      case defn.FunctionType(args, resultType) =>
        narrowByTypes(alts, args, resultType)

      case tp =>
        alts filter (alt => testCompatible(normalize(alt), tp))
    }

    if (isDetermined(candidates)) candidates
    else narrowMostSpecific(candidates)(ctx.addMode(ImplicitsDisabled))
  }
}