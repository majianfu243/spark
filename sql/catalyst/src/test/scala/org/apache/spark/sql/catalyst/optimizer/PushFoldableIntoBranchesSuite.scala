/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import java.sql.Date

import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.Literal.{FalseLiteral, TrueLiteral}
import org.apache.spark.sql.catalyst.plans.PlanTest
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.types.{BooleanType, IntegerType, StringType}


class PushFoldableIntoBranchesSuite
  extends PlanTest with ExpressionEvalHelper with PredicateHelper {

  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches = Batch("PushFoldableIntoBranches", FixedPoint(50),
      BooleanSimplification, ConstantFolding, SimplifyConditionals, PushFoldableIntoBranches) :: Nil
  }

  private val relation = LocalRelation('a.int, 'b.int, 'c.boolean)
  private val a = EqualTo(UnresolvedAttribute("a"), Literal(100))
  private val b = UnresolvedAttribute("b")
  private val c = EqualTo(UnresolvedAttribute("c"), Literal(true))
  private val ifExp = If(a, Literal(2), Literal(3))
  private val caseWhen = CaseWhen(Seq((a, Literal(1)), (c, Literal(2))), Some(Literal(3)))

  protected def assertEquivalent(e1: Expression, e2: Expression): Unit = {
    val correctAnswer = Project(Alias(e2, "out")() :: Nil, relation).analyze
    val actual = Optimize.execute(Project(Alias(e1, "out")() :: Nil, relation).analyze)
    comparePlans(actual, correctAnswer)
  }

  test("Push down EqualTo through If") {
    assertEquivalent(EqualTo(ifExp, Literal(4)), FalseLiteral)
    assertEquivalent(EqualTo(ifExp, Literal(3)), Not(a))

    // Push down at most one not foldable expressions.
    assertEquivalent(
      EqualTo(If(a, b, Literal(2)), Literal(2)),
      If(a, EqualTo(b, Literal(2)), TrueLiteral))
    assertEquivalent(
      EqualTo(If(a, b, b + 1), Literal(2)),
      EqualTo(If(a, b, b + 1), Literal(2)))

    // Push down non-deterministic expressions.
    val nonDeterministic = If(LessThan(Rand(1), Literal(0.5)), Literal(1), Literal(2))
    assert(!nonDeterministic.deterministic)
    assertEquivalent(EqualTo(nonDeterministic, Literal(2)),
      GreaterThanOrEqual(Rand(1), Literal(0.5)))
    assertEquivalent(EqualTo(nonDeterministic, Literal(3)),
      If(LessThan(Rand(1), Literal(0.5)), FalseLiteral, FalseLiteral))

    // Handle Null values.
    assertEquivalent(
      EqualTo(If(a, Literal(null, IntegerType), Literal(1)), Literal(1)),
      If(a, Literal(null, BooleanType), TrueLiteral))
    assertEquivalent(
      EqualTo(If(a, Literal(null, IntegerType), Literal(1)), Literal(2)),
      If(a, Literal(null, BooleanType), FalseLiteral))
    assertEquivalent(
      EqualTo(If(a, Literal(1), Literal(2)), Literal(null, IntegerType)),
      Literal(null, BooleanType))
    assertEquivalent(
      EqualTo(If(a, Literal(null, IntegerType), Literal(null, IntegerType)), Literal(1)),
      Literal(null, BooleanType))
  }

  test("Push down other BinaryComparison through If") {
    assertEquivalent(EqualNullSafe(ifExp, Literal(4)), FalseLiteral)
    assertEquivalent(GreaterThan(ifExp, Literal(4)), FalseLiteral)
    assertEquivalent(GreaterThanOrEqual(ifExp, Literal(4)), FalseLiteral)
    assertEquivalent(LessThan(ifExp, Literal(4)), TrueLiteral)
    assertEquivalent(LessThanOrEqual(ifExp, Literal(4)), TrueLiteral)
  }

  test("Push down other BinaryOperator through If") {
    assertEquivalent(Add(ifExp, Literal(4)), If(a, Literal(6), Literal(7)))
    assertEquivalent(Subtract(ifExp, Literal(4)), If(a, Literal(-2), Literal(-1)))
    assertEquivalent(Multiply(ifExp, Literal(4)), If(a, Literal(8), Literal(12)))
    assertEquivalent(Pmod(ifExp, Literal(4)), If(a, Literal(2), Literal(3)))
    assertEquivalent(Remainder(ifExp, Literal(4)), If(a, Literal(2), Literal(3)))
    assertEquivalent(Divide(If(a, Literal(2.0), Literal(3.0)), Literal(1.0)),
      If(a, Literal(2.0), Literal(3.0)))
    assertEquivalent(And(If(a, FalseLiteral, TrueLiteral), TrueLiteral), Not(a))
    assertEquivalent(Or(If(a, FalseLiteral, TrueLiteral), TrueLiteral), TrueLiteral)
  }

  test("Push down other BinaryExpression through If") {
    assertEquivalent(BRound(If(a, Literal(1.23), Literal(1.24)), Literal(1)), Literal(1.2))
    assertEquivalent(StartsWith(If(a, Literal("ab"), Literal("ac")), Literal("a")), TrueLiteral)
    assertEquivalent(FindInSet(If(a, Literal("ab"), Literal("ac")), Literal("a")), Literal(0))
    assertEquivalent(
      AddMonths(If(a, Literal(Date.valueOf("2020-01-01")), Literal(Date.valueOf("2021-01-01"))),
        Literal(1)),
      If(a, Literal(Date.valueOf("2020-02-01")), Literal(Date.valueOf("2021-02-01"))))
  }

  test("Push down EqualTo through CaseWhen") {
    assertEquivalent(EqualTo(caseWhen, Literal(4)), FalseLiteral)
    assertEquivalent(EqualTo(caseWhen, Literal(3)),
      CaseWhen(Seq((a, FalseLiteral), (c, FalseLiteral)), Some(TrueLiteral)))
    assertEquivalent(
      EqualTo(CaseWhen(Seq((a, Literal(1)), (c, Literal(2))), None), Literal(4)),
      CaseWhen(Seq((a, FalseLiteral), (c, FalseLiteral)), None))

    assertEquivalent(
      And(EqualTo(caseWhen, Literal(5)), EqualTo(caseWhen, Literal(6))),
      FalseLiteral)

    // Push down at most one branch is not foldable expressions.
    assertEquivalent(EqualTo(CaseWhen(Seq((a, b), (c, Literal(1))), None), Literal(1)),
      CaseWhen(Seq((a, EqualTo(b, Literal(1))), (c, TrueLiteral)), None))
    assertEquivalent(EqualTo(CaseWhen(Seq((a, b), (c, b + 1)), None), Literal(1)),
      EqualTo(CaseWhen(Seq((a, b), (c, b + 1)), None), Literal(1)))
    assertEquivalent(EqualTo(CaseWhen(Seq((a, b)), None), Literal(1)),
      EqualTo(CaseWhen(Seq((a, b)), None), Literal(1)))

    // Push down non-deterministic expressions.
    val nonDeterministic =
      CaseWhen(Seq((LessThan(Rand(1), Literal(0.5)), Literal(1))), Some(Literal(2)))
    assert(!nonDeterministic.deterministic)
    assertEquivalent(EqualTo(nonDeterministic, Literal(2)),
      CaseWhen(Seq((LessThan(Rand(1), Literal(0.5)), FalseLiteral)), Some(TrueLiteral)))
    assertEquivalent(EqualTo(nonDeterministic, Literal(3)),
      CaseWhen(Seq((LessThan(Rand(1), Literal(0.5)), FalseLiteral)), Some(FalseLiteral)))

    // Handle Null values.
    assertEquivalent(
      EqualTo(CaseWhen(Seq((a, Literal(null, IntegerType))), Some(Literal(1))), Literal(2)),
      CaseWhen(Seq((a, Literal(null, BooleanType))), Some(FalseLiteral)))
    assertEquivalent(
      EqualTo(CaseWhen(Seq((a, Literal(1))), Some(Literal(2))), Literal(null, IntegerType)),
      Literal(null, BooleanType))
    assertEquivalent(
      EqualTo(CaseWhen(Seq((a, Literal(null, IntegerType))), Some(Literal(1))), Literal(1)),
      CaseWhen(Seq((a, Literal(null, BooleanType))), Some(TrueLiteral)))
    assertEquivalent(
      EqualTo(CaseWhen(Seq((a, Literal(null, IntegerType))), Some(Literal(null, IntegerType))),
        Literal(1)),
      Literal(null, BooleanType))
    assertEquivalent(
      EqualTo(CaseWhen(Seq((a, Literal(null, IntegerType))), Some(Literal(null, IntegerType))),
        Literal(null, IntegerType)),
      Literal(null, BooleanType))
  }

  test("Push down other BinaryComparison through CaseWhen") {
    assertEquivalent(EqualNullSafe(caseWhen, Literal(4)), FalseLiteral)
    assertEquivalent(GreaterThan(caseWhen, Literal(4)), FalseLiteral)
    assertEquivalent(GreaterThanOrEqual(caseWhen, Literal(4)), FalseLiteral)
    assertEquivalent(LessThan(caseWhen, Literal(4)), TrueLiteral)
    assertEquivalent(LessThanOrEqual(caseWhen, Literal(4)), TrueLiteral)
  }

  test("Push down other BinaryOperator through CaseWhen") {
    assertEquivalent(Add(caseWhen, Literal(4)),
      CaseWhen(Seq((a, Literal(5)), (c, Literal(6))), Some(Literal(7))))
    assertEquivalent(Subtract(caseWhen, Literal(4)),
      CaseWhen(Seq((a, Literal(-3)), (c, Literal(-2))), Some(Literal(-1))))
    assertEquivalent(Multiply(caseWhen, Literal(4)),
      CaseWhen(Seq((a, Literal(4)), (c, Literal(8))), Some(Literal(12))))
    assertEquivalent(Pmod(caseWhen, Literal(4)),
      CaseWhen(Seq((a, Literal(1)), (c, Literal(2))), Some(Literal(3))))
    assertEquivalent(Remainder(caseWhen, Literal(4)),
      CaseWhen(Seq((a, Literal(1)), (c, Literal(2))), Some(Literal(3))))
    assertEquivalent(Divide(CaseWhen(Seq((a, Literal(1.0)), (c, Literal(2.0))), Some(Literal(3.0))),
      Literal(1.0)),
      CaseWhen(Seq((a, Literal(1.0)), (c, Literal(2.0))), Some(Literal(3.0))))
    assertEquivalent(And(CaseWhen(Seq((a, FalseLiteral), (c, TrueLiteral)), Some(TrueLiteral)),
      TrueLiteral),
      CaseWhen(Seq((a, FalseLiteral), (c, TrueLiteral)), Some(TrueLiteral)))
    assertEquivalent(Or(CaseWhen(Seq((a, FalseLiteral), (c, TrueLiteral)), Some(TrueLiteral)),
      TrueLiteral), TrueLiteral)
  }

  test("Push down other BinaryExpression through CaseWhen") {
    assertEquivalent(
      BRound(CaseWhen(Seq((a, Literal(1.23)), (c, Literal(1.24))), Some(Literal(1.25))),
        Literal(1)),
      Literal(1.2))
    assertEquivalent(
      StartsWith(CaseWhen(Seq((a, Literal("ab")), (c, Literal("ac"))), Some(Literal("ad"))),
        Literal("a")),
      TrueLiteral)
    assertEquivalent(
      FindInSet(CaseWhen(Seq((a, Literal("ab")), (c, Literal("ac"))), Some(Literal("ad"))),
        Literal("a")),
      Literal(0))
    assertEquivalent(
      AddMonths(CaseWhen(Seq((a, Literal(Date.valueOf("2020-01-01"))),
        (c, Literal(Date.valueOf("2021-01-01")))),
        Some(Literal(Date.valueOf("2022-01-01")))),
        Literal(1)),
      CaseWhen(Seq((a, Literal(Date.valueOf("2020-02-01"))),
        (c, Literal(Date.valueOf("2021-02-01")))),
        Some(Literal(Date.valueOf("2022-02-01")))))
  }

  test("Push down BinaryExpression through If/CaseWhen backwards") {
    assertEquivalent(EqualTo(Literal(4), ifExp), FalseLiteral)
    assertEquivalent(EqualTo(Literal(4), caseWhen), FalseLiteral)
  }

  test("SPARK-33848: Push down cast through If/CaseWhen") {
    assertEquivalent(If(a, Literal(2), Literal(3)).cast(StringType),
      If(a, Literal("2"), Literal("3")))
    assertEquivalent(If(a, b, Literal(3)).cast(StringType),
      If(a, b.cast(StringType), Literal("3")))
    assertEquivalent(If(a, b, b + 1).cast(StringType),
      If(a, b, b + 1).cast(StringType))

    assertEquivalent(
      CaseWhen(Seq((a, Literal(1))), Some(Literal(3))).cast(StringType),
      CaseWhen(Seq((a, Literal("1"))), Some(Literal("3"))))
    assertEquivalent(
      CaseWhen(Seq((a, Literal(1))), Some(b)).cast(StringType),
      CaseWhen(Seq((a, Literal("1"))), Some(b.cast(StringType))))
    assertEquivalent(
      CaseWhen(Seq((a, b)), Some(b + 1)).cast(StringType),
      CaseWhen(Seq((a, b)), Some(b + 1)).cast(StringType))
  }

  test("SPARK-33848: Push down abs through If/CaseWhen") {
    assertEquivalent(Abs(If(a, Literal(-2), Literal(-3))), If(a, Literal(2), Literal(3)))
    assertEquivalent(
      Abs(CaseWhen(Seq((a, Literal(-1))), Some(Literal(-3)))),
      CaseWhen(Seq((a, Literal(1))), Some(Literal(3))))
  }

  test("SPARK-33848: Push down cast with binary expression through If/CaseWhen") {
    assertEquivalent(EqualTo(If(a, Literal(2), Literal(3)).cast(StringType), Literal("4")),
      FalseLiteral)
    assertEquivalent(
      EqualTo(CaseWhen(Seq((a, Literal(1))), Some(Literal(3))).cast(StringType), Literal("4")),
      FalseLiteral)
    assertEquivalent(
      EqualTo(CaseWhen(Seq((a, Literal(1)), (c, Literal(2))), None).cast(StringType), Literal("4")),
      CaseWhen(Seq((a, FalseLiteral), (c, FalseLiteral)), None))
  }
}
