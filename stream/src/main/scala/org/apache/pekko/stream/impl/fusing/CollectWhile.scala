/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.stream.impl.fusing

import scala.util.control.NonFatal

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.stream.{ Attributes, FlowShape, Inlet, Outlet, Supervision }
import pekko.stream.ActorAttributes.SupervisionStrategy
import pekko.stream.Attributes.SourceLocation
import pekko.stream.impl.Stages.DefaultAttributes
import pekko.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] final class CollectWhile[In, Out](pf: PartialFunction[In, Out]) extends GraphStage[FlowShape[In, Out]] {
  private val in = Inlet[In]("CollectWhile.in")
  private val out = Outlet[Out]("CollectWhile.out")
  override val shape = FlowShape(in, out)

  override def initialAttributes: Attributes = DefaultAttributes.collectWhile and SourceLocation.forLambda(pf)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
      import Collect.NotApplied

      override final def onPush(): Unit =
        try {
          // 1. `applyOrElse` is faster than (`pf.isDefinedAt` and then `pf.apply`)
          // 2. using reference comparing here instead of pattern matching can generate less and quicker bytecode,
          //   eg: just a simple `IF_ACMPNE`, and you can find the same trick in `Collect` operator.
          //   If you interest, you can check the associated PR for this change and the
          //   current implementation of `scala.collection.IterableOnceOps.collectFirst`.
          val result = pf.applyOrElse(grab(in), NotApplied)
          if (result.asInstanceOf[AnyRef] eq NotApplied) {
            completeStage()
          } else {
            push(out, result.asInstanceOf[Out])
          }
        } catch {
          case NonFatal(ex) =>
            decider(ex) match {
              case Supervision.Stop => failStage(ex)
              case _                =>
                // The !hasBeenPulled(in) check is not required here since it
                // isn't possible to do an additional pull(in) due to the nature
                // of how collect works
                pull(in)
            }
        }

      override final def onPull(): Unit = pull(in)

      setHandlers(in, out, this)
    }

  override def toString: String = "CollectWhile"
}
