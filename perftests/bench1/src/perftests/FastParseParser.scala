package perftests

import fastparse.{WhitespaceApi, core}
import perftests.Expr.Member.Visibility

class FastParseParser{
  val parseCache = collection.mutable.Map.empty[String, fastparse.all.Parsed[Expr]]

  val precedenceTable = Seq(
    Seq("*", "/", "%"),
    Seq("+", "-"),
    Seq("<<", ">>"),
    Seq("<", ">", "<=", ">=", "in"),
    Seq("==", "!="),
    Seq("&"),
    Seq("^"),
    Seq("|"),
    Seq("&&"),
    Seq("||")
  )
  val precedence = precedenceTable
    .reverse
    .zipWithIndex
    .flatMap{case (ops, idx) => ops.map(_ -> idx)}
    .toMap

  val White = WhitespaceApi.Wrapper {
    import fastparse.all._
    // Custom parser for whitespace consumption, since this parser is used
    // everywhere and thus dominates performance when written normally using
    // combinators
    new P[Unit]{
      def parseRec(cfg: core.ParseCtx[Char, String], index: Int) = {
        def rec(current: Int, state: Int): Mutable.Success[Unit] = {
          if (current >= cfg.input.length) this.success(cfg.success, (), current, Set.empty, false)
          else state match{
            case 0 =>
              cfg.input(current) match{
                case ' ' | '\t' | '\n' | '\r' => rec(current + 1, state)
                case '#' => rec(current + 1, state = 1)
                case '/' => rec(current + 1, state = 2)
                case _ => this.success(cfg.success, (), current, Set.empty, false)
              }
            case 1 =>
              cfg.input(current) match{
                case '\n' => rec(current + 1, state = 0)
                case _ => rec(current + 1, state)
              }
            case 2 =>
              cfg.input(current) match{
                case '/' => rec(current + 1, state = 1)
                case '*' => rec(current + 1, state = 3)
                case _ => this.success(cfg.success, (), current - 1, Set.empty, false)
              }
            case 3 =>
              cfg.input(current) match{
                case '*' => rec(current + 1, state = 4)
                case _ => rec(current + 1, state)
              }
            case 4 =>
              cfg.input(current) match{
                case '/' => rec(current + 1, state = 0)
                case _ => rec(current + 1, state = 3)
              }
          }
        }
        rec(current = index, state = 0)
      }
    }
  }
  import White._
  import fastparse.noApi._

  val keywords = Set(
    "assert", "else", "error", "false", "for", "function", "if", "import", "importstr",
    "in", "local", "null", "tailstrict", "then", "self", "super", "true"
  )
  val id = P(
    CharIn("_" ++ ('a' to 'z') ++ ('A' to 'Z')) ~~
      CharsWhileIn("_" ++ ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9'), min = 0)
  ).!.filter(s => !keywords.contains(s))

  val break = P(!CharIn("_" ++ ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')))
  val number: P[Expr.Num] = P(
    Index ~~ (
      CharsWhileIn('0' to '9') ~~
        ("." ~ CharsWhileIn('0' to '9')).? ~~
        (("e" | "E") ~ ("+" | "-").? ~~ CharsWhileIn('0' to '9')).?
      ).!
  ).map(s => Expr.Num(s._1, s._2.toDouble))

  val escape = P( escape0 | escape1 )
  val escape0 = P("\\" ~~ !"u" ~~ AnyChar.!).map{
    case "\"" => "\""
    case "'" => "\'"
    case "\\" => "\\"
    case "/" => "/"
    case "b" => "\b"
    case "f" => "\f"
    case "n" => "\n"
    case "r" => "\r"
    case "t" => "\t"
  }
  val escape1 = P( "\\u" ~~ CharIn('0' to '9').repX(min=4, max=4).! ).map{
    s => Integer.parseInt(s, 16).toChar.toString
  }
  val string: P[String] = P(
    "\"".~/ ~~ (CharsWhile(x => x != '"' && x != '\\').! | escape).repX ~~ "\"" |
      "'".~/ ~~ (CharsWhile(x => x != '\'' && x != '\\').! | escape).repX ~~ "'" |
      "@\"".~/ ~~ (CharsWhile(_ != '"').! | "\"\"".!.map(_ => "\"")).repX ~~ "\"" |
      "@'".~/ ~~ (CharsWhile(_ != '\'').! | "''".!.map(_ => "'")).repX ~~ "'" |
      "|||".~/ ~~ CharsWhileIn(" \t", 0) ~~ "\n" ~~ tripleBarStringHead.flatMap { case (pre, w, head) =>
        tripleBarCache.getOrElseUpdate(w, tripleBarStringBody(w)).map(pre ++ Seq(head, "\n") ++ _)
      } ~~ "\n" ~~ CharsWhileIn(" \t", min=0) ~~ "|||"
  ).map(_.mkString).`opaque`()

  val tripleBarStringHead = P(
    (CharsWhileIn(" \t", min=0) ~~ "\n".!).repX ~~
      CharsWhileIn(" \t", min=1).! ~~
      CharsWhile(_ != '\n').!
  )
  val tripleBarBlank = P( "\n" ~~ CharsWhileIn(" \t", min=0) ~~ &("\n").map(_ => "\n") )
  val tripleBarCache = collection.mutable.Map.empty[String, P[Seq[String]]]
  def tripleBarStringBody(w: String) = P(
    (tripleBarBlank | "\n" ~~ w ~~ CharsWhile(_ != '\n').!.map(_ + "\n")).repX
  )

  val `null` = P(Index ~ "null" ~~ break).map(Expr.Null)
  val `true` = P(Index ~ "true" ~~ break).map(Expr.True)
  val `false` = P(Index ~ "false" ~~ break).map(Expr.False)
  val `self` = P(Index ~ "self" ~~ break).map(Expr.Self)
  val $ = P(Index ~ "$").map(Expr.$)
  val `super` = P(Index ~ "super" ~~ break).map(Expr.Super)

  val obj: P[Expr] = P( "{" ~/ (Index ~ objinside).map(Expr.Obj.tupled) ~ "}" )
  val arr: P[Expr] = P(
    "[" ~/ ((Index ~ "]").map(Expr.Arr(_, Nil)) | arrBody ~ "]")
  )
  val compSuffix = P( forspec ~ compspec ).map(Left(_))
  val arrBody: P[Expr] = P(
    Index ~ expr ~ (compSuffix | "," ~/ (compSuffix | (expr.rep(0, sep = ",") ~ ",".?).map(Right(_)))).?
  ).map{
    case (offset, first, None) => Expr.Arr(offset, Seq(first))
    case (offset, first, Some(Left(comp))) => Expr.Comp(offset, first, comp._1, comp._2)
    case (offset, first, Some(Right(rest))) => Expr.Arr(offset, Seq(first) ++ rest)
  }
  val assertExpr: P[Expr] = P( Index ~ assertStmt ~/ ";" ~ expr ).map(Expr.AssertExpr.tupled)
  val function: P[Expr] = P( Index ~ "function" ~ "(" ~/ params ~ ")" ~ expr ).map(Expr.Function.tupled)
  val ifElse: P[Expr] = P( Index ~ expr ~ "then" ~~ break ~ expr ~ ("else" ~~ break ~ expr).? ).map(Expr.IfElse.tupled)
  val localExpr: P[Expr] = P( Index ~ bind.rep(min=1, sep = ","~/) ~ ";" ~ expr ).map(Expr.LocalExpr.tupled)

  val expr: P[Expr] = P("" ~ expr1 ~ (Index ~ binaryop ~/ expr1).rep ~ "").map{ case (pre, fs) =>
    var remaining = fs
    def climb(minPrec: Int, current: Expr): Expr = {
      var result = current
      while(
        remaining.headOption match{
          case None => false
          case Some((offset, op, next)) =>
            val prec: Int = precedence(op)
            if (prec < minPrec) false
            else{
              remaining = remaining.tail
              val rhs = climb(prec + 1, next)
              val op1 = op match{
                case "*" => Expr.BinaryOp.`*`
                case "/" => Expr.BinaryOp.`/`
                case "%" => Expr.BinaryOp.`%`
                case "+" => Expr.BinaryOp.`+`
                case "-" => Expr.BinaryOp.`-`
                case "<<" => Expr.BinaryOp.`<<`
                case ">>" => Expr.BinaryOp.`>>`
                case "<" => Expr.BinaryOp.`<`
                case ">" => Expr.BinaryOp.`>`
                case "<=" => Expr.BinaryOp.`<=`
                case ">=" => Expr.BinaryOp.`>=`
                case "in" => Expr.BinaryOp.`in`
                case "==" => Expr.BinaryOp.`==`
                case "!=" => Expr.BinaryOp.`!=`
                case "&" => Expr.BinaryOp.`&`
                case "^" => Expr.BinaryOp.`^`
                case "|" => Expr.BinaryOp.`|`
                case "&&" => Expr.BinaryOp.`&&`
                case "||" => Expr.BinaryOp.`||`
              }
              result = Expr.BinaryOp(offset, result, op1, rhs)
              true
            }
        }
      )()
      result
    }

    climb(0, pre)
  }

  val expr1: P[Expr] = P(expr2 ~ exprSuffix2.rep).map{
    case (pre, fs) => fs.foldLeft(pre){case (p, f) => f(p) }
  }

  val exprSuffix2: P[Expr => Expr] = P(
    (Index ~ "." ~/ id).map(x => Expr.Select(x._1, _: Expr, x._2)) |
      (Index ~ "[" ~/ expr.? ~ (":" ~ expr.?).rep ~ "]").map{
        case (offset, Some(tree), Seq()) => Expr.Lookup(offset, _: Expr, tree)
        case (offset, start, ins) => Expr.Slice(offset, _: Expr, start, ins.lift(0).flatten, ins.lift(1).flatten)
      } |
      (Index ~ "(" ~/ args ~ ")").map(x => Expr.Apply(x._1, _: Expr, x._2)) |
      (Index ~ "{" ~/ objinside ~ "}").map(x => Expr.ObjExtend(x._1, _: Expr, x._2))
  )

  // Any `expr` that isn't naively left-recursive
  val expr2 = P(
    `null` | `true` | `false` | `self` | $ | number |
      (Index ~ string).map(Expr.Str.tupled) | obj | arr | `super`
      | (Index ~ id).map(Expr.Id.tupled)
      | "local" ~~ break  ~/ localExpr
      | "(" ~/ (Index ~ expr).map(Expr.Parened.tupled) ~ ")"
      | "if" ~~ break ~/ ifElse
      | function
      | (Index ~ "importstr" ~/ string).map(Expr.ImportStr.tupled)
      | (Index ~ "import" ~/ string).map(Expr.Import.tupled)
      | (Index ~ "error" ~~ break ~/ expr).map(Expr.Error.tupled)
      | assertExpr
      | (Index ~ unaryop ~/ expr1).map{ case (i, k, e) =>
      val k2 = k match{
        case "+" => Expr.UnaryOp.`+`
        case "-" => Expr.UnaryOp.`-`
        case "~" => Expr.UnaryOp.`~`
        case "!" => Expr.UnaryOp.`!`
      }
      Expr.UnaryOp(i, k2, e)
    }
  )

  val objinside: P[Expr.ObjBody] = P(
    member.rep(sep = ",") ~ ",".? ~ (forspec ~ compspec).?
  ).map{
    case (exprs, None) => Expr.ObjBody.MemberList(exprs)
    case (exprs, Some(comps)) =>
      val preLocals = exprs.takeWhile(_.isInstanceOf[Expr.Member.BindStmt]).map(_.asInstanceOf[Expr.Member.BindStmt])
      val Expr.Member.Field(offset, Expr.FieldName.Dyn(lhs), false, None, Visibility.Normal, rhs) =
        exprs(preLocals.length)
      val postLocals = exprs.drop(preLocals.length+1).takeWhile(_.isInstanceOf[Expr.Member.BindStmt])
        .map(_.asInstanceOf[Expr.Member.BindStmt])
      Expr.ObjBody.ObjComp(preLocals, lhs, rhs, postLocals, comps._1, comps._2)
  }

  val member: P[Expr.Member] = P( objlocal | assertStmt | field )
  val field = P(
    (Index ~ fieldname ~/ "+".!.? ~ ("(" ~ params ~ ")").? ~ fieldKeySep ~ expr).map{
      case (offset, name, plus, p, h2, e) =>
        Expr.Member.Field(offset, name, plus.nonEmpty, p, h2, e)
    }
  )
  val fieldKeySep = P( ":::" | "::" | ":" ).!.map{
    case ":" => Visibility.Normal
    case "::" => Visibility.Hidden
    case ":::" => Visibility.Unhide
  }
  val objlocal = P( "local" ~~ break ~/ bind ).map(Expr.Member.BindStmt)
  val compspec: P[Seq[Expr.CompSpec]] = P( (forspec | ifspec).rep )
  val forspec = P( Index ~ "for" ~~ break ~/ id ~ "in" ~~ break ~ expr ).map(Expr.ForSpec.tupled)
  val ifspec = P( Index ~ "if" ~~ break  ~/ expr ).map(Expr.IfSpec.tupled)
  val fieldname = P( id.map(Expr.FieldName.Fixed) | string.map(Expr.FieldName.Fixed) | "[" ~ expr.map(Expr.FieldName.Dyn) ~ "]" )
  val assertStmt = P( "assert" ~~ break  ~/ expr ~ (":" ~ expr).? ).map(Expr.Member.AssertStmt.tupled)
  val bind = P( Index ~ id ~ ("(" ~/ params.? ~ ")").?.map(_.flatten) ~ "=" ~ expr ).map(Expr.Bind.tupled)
  val args = P( ((id ~ "=").? ~ expr).rep(sep = ",") ~ ",".? ).flatMap{x =>
    if (x.sliding(2).exists{case Seq(l, r) => l._1.isDefined && r._1.isEmpty case _ => false}) {
      Fail.`opaque`("no positional params after named params")
    } else Pass.map(_ => Expr.Args(x))


  }

  val params: P[Expr.Params] = P( (id ~ ("=" ~ expr).?).rep(sep = ",") ~ ",".? ).flatMap{x =>
    val seen = collection.mutable.Set.empty[String]
    var overlap: String = null
    for((k, v) <- x){
      if (seen(k)) overlap = k
      else seen.add(k)
    }
    if (overlap == null) Pass.map(_ => Expr.Params(x))
    else Fail.`opaque`("no duplicate parameter: " + overlap)

  }

  val binaryop = P( precedenceTable.flatten.sortBy(-_.length).map(LiteralStr).reduce(_ | _) ).!
  val unaryop	= P("-" | "+" | "!" | "~").!

  val document = P( expr ~ End )
}