/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_4.planner.logical

import org.neo4j.cypher.internal.compiler.v3_4.phases._
import org.neo4j.cypher.internal.compiler.v3_4.planner.logical.Metrics.{CostModel, QueryGraphSolverInput}
import org.neo4j.cypher.internal.compiler.v3_4.planner.logical.idp.{Goal, IDPCache, IdRegistry}
import org.neo4j.cypher.internal.compiler.v3_4.planner.logical.steps.LogicalPlanProducer
import org.neo4j.cypher.internal.frontend.v3_4.phases.CompilationPhaseTracer.CompilationPhase.LOGICAL_PLANNING
import org.neo4j.cypher.internal.frontend.v3_4.phases.Phase
import org.neo4j.cypher.internal.ir.v3_4.{PeriodicCommit, PlannerQuery, UnionQuery}
import org.neo4j.cypher.internal.util.v3_4.Cost
import org.neo4j.cypher.internal.v3_4.logical.plans.{LogicalPlan, ProduceResult}

case class QueryPlanner(planSingleQuery: ((PlannerQuery, LogicalPlanningContext) => LogicalPlan) = PlanSingleQuery()) extends Phase[CompilerContext, LogicalPlanState, LogicalPlanState] {


  override def phase = LOGICAL_PLANNING

  override def description = "using cost estimates, plan the query to a logical plan"

  override def postConditions = Set(CompilationContains[LogicalPlan])

  override def process(from: LogicalPlanState, context: CompilerContext): LogicalPlanState = {
    val logicalPlanProducer = LogicalPlanProducer(context.metrics.cardinality, LogicalPlan.LOWEST_TX_LAYER, context.logicalPlanIdGen)
    val logicalPlanningContext = LogicalPlanningContext(
      planContext = context.planContext,
      logicalPlanProducer = logicalPlanProducer,
      metrics = getMetricsFrom(context),
      semanticTable = from.semanticTable(),
      strategy = context.queryGraphSolver,
      notificationLogger = context.notificationLogger,
      useErrorsOverWarnings = context.config.useErrorsOverWarnings,
      errorIfShortestPathFallbackUsedAtRuntime = context.config.errorIfShortestPathFallbackUsedAtRuntime,
      errorIfShortestPathHasCommonNodesAtRuntime = context.config.errorIfShortestPathHasCommonNodesAtRuntime,
      config = QueryPlannerConfiguration.default.withUpdateStrategy(context.updateStrategy),
      legacyCsvQuoteEscaping = context.config.legacyCsvQuoteEscaping
    )

    val (perCommit, logicalPlan) = plan(from.unionQuery, logicalPlanningContext)
    from.copy(maybePeriodicCommit = Some(perCommit), maybeLogicalPlan = Some(logicalPlan))
  }

  private def getMetricsFrom(context: CompilerContext) = if (context.debugOptions.contains("inverse_cost")) {
    context.metrics.copy(cost = new CostModel {
      override def apply(v1: LogicalPlan, v2: QueryGraphSolverInput): Cost = -context.metrics.cost(v1, v2)
    })
  } else {
    context.metrics
  }

  def plan(unionQuery: UnionQuery, context: LogicalPlanningContext): (Option[PeriodicCommit], LogicalPlan) =
    unionQuery match {
      case UnionQuery(queries, distinct, _, periodicCommitHint) =>
        val plan = planQueries(queries, distinct, context)
        (periodicCommitHint, createProduceResultOperator(plan, unionQuery, context))
    }

  private def createProduceResultOperator(in: LogicalPlan,
                                          unionQuery: UnionQuery,
                                          context: LogicalPlanningContext): LogicalPlan =
  context.logicalPlanProducer.planProduceResult(in, unionQuery.returns.map(_.name))

  private def planQueries(queries: Seq[PlannerQuery], distinct: Boolean, context: LogicalPlanningContext) = {
    val logicalPlans: Seq[LogicalPlan] = queries.map(p => planSingleQuery(p, context))
    val unionPlan = logicalPlans.reduce[LogicalPlan] {
      case (p1, p2) => context.logicalPlanProducer.planUnion(p1, p2, context)
    }

    if (distinct)
      context.logicalPlanProducer.planDistinctStar(unionPlan, context)
    else
      unionPlan
  }
}

case object planPart extends ((PlannerQuery, LogicalPlanningContext) => LogicalPlan) {

  def apply(query: PlannerQuery, context: LogicalPlanningContext): LogicalPlan = {
    val ctx = query.preferredStrictness match {
      case Some(mode) if !context.input.strictness.contains(mode) => context.withStrictness(mode)
      case _ => context
    }
    ctx.strategy.plan(query.queryGraph, ctx)
  }
}
