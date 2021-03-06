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

package org.apache.spark.mllib.tree

import org.apache.spark.Logging
import org.apache.spark.annotation.Experimental
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.configuration.BoostingStrategy
import org.apache.spark.mllib.tree.configuration.Algo._
import org.apache.spark.mllib.tree.impl.TimeTracker
import org.apache.spark.mllib.tree.impurity.Variance_new
import org.apache.spark.mllib.tree.model.{DecisionTreeModel_new, GradientBoostedTreesModel}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import org.apache.log4j._

/**
 * :: Experimental ::
 * A class that implements Stochastic Gradient Boosting for regression and binary classification.
 *
 * The implementation is based upon:
 *   J.H. Friedman.  "Stochastic Gradient Boosting."  1999.
 *
 * Notes:
 *  - This currently can be run with several loss functions.  However, only SquaredError is
 *    fully supported.  Specifically, the loss function should be used to compute the gradient
 *    (to re-label training instances on each iteration) and to weight weak hypotheses.
 *    Currently, gradients are computed correctly for the available loss functions,
 *    but weak hypothesis weights are not computed correctly for LogLoss or AbsoluteError.
 *    Running with those losses will likely behave reasonably, but lacks the same guarantees.
 *
 * @param boostingStrategy Parameters for the gradient boosting algorithm.
 */
@Experimental
class GradientBoostedTrees(private val boostingStrategy: BoostingStrategy)
  extends Serializable with Logging {

  /**
   * Method to train a gradient boosting model
   * @param input Training dataset: RDD of [[org.apache.spark.mllib.regression.LabeledPoint]].
   * @return a gradient boosted trees model that can be used for prediction
   */
  def run(input: RDD[LabeledPoint]): GradientBoostedTreesModel = {
    val algo = boostingStrategy.treeStrategy.algo
    algo match {
      case Regression => GradientBoostedTrees.boost(input, boostingStrategy)
      case Classification =>
        // Map labels to -1, +1 so binary classification can be treated as regression.
        val remappedInput = input.map(x => new LabeledPoint((x.label * 2) - 1, x.features))
        GradientBoostedTrees.boost(remappedInput, boostingStrategy)
      case _ =>
        throw new IllegalArgumentException(s"$algo is not supported by the gradient boosting.")
    }
  }

  /**
   * Java-friendly API for [[org.apache.spark.mllib.tree.GradientBoostedTrees!#run]].
   */
  def run(input: JavaRDD[LabeledPoint]): GradientBoostedTreesModel = {
    run(input.rdd)
  }
}


object GradientBoostedTrees extends Logging {

  /**
   * Method to train a gradient boosting model.
   *
   * @param input Training dataset: RDD of [[org.apache.spark.mllib.regression.LabeledPoint]].
   *              For classification, labels should take values {0, 1, ..., numClasses-1}.
   *              For regression, labels are real numbers.
   * @param boostingStrategy Configuration options for the boosting algorithm.
   * @return a gradient boosted trees model that can be used for prediction
   */
  def train(
      input: RDD[LabeledPoint],
      boostingStrategy: BoostingStrategy): GradientBoostedTreesModel = {
    new GradientBoostedTrees(boostingStrategy).run(input)
  }

  /**
   * Java-friendly API for [[org.apache.spark.mllib.tree.GradientBoostedTrees$#train]]
   */
  def train(
      input: JavaRDD[LabeledPoint],
      boostingStrategy: BoostingStrategy): GradientBoostedTreesModel = {
    train(input.rdd, boostingStrategy)
  }

  /**
   * Internal method for performing regression using trees as base learners.
   * @param input training dataset
   * @param boostingStrategy boosting parameters
   * @return a gradient boosted trees model that can be used for prediction
   */
  private def boost(
      input: RDD[LabeledPoint],
      boostingStrategy: BoostingStrategy): GradientBoostedTreesModel = {

    val timer = new TimeTracker()
    timer.start("total")
    timer.start("init")

    boostingStrategy.assertValid()

    // Initialize gradient boosting parameters
    val numIterations = boostingStrategy.numIterations
    val baseLearners = new Array[DecisionTreeModel_new](numIterations)
    val baseLearnerWeights = new Array[Double](numIterations)
    val loss = boostingStrategy.loss
    val learningRate = boostingStrategy.learningRate
    // Prepare strategy for individual trees, which use regression with variance impurity.
    val treeStrategy = boostingStrategy.treeStrategy.copy
    treeStrategy.algo = Regression
    treeStrategy.impurity = Variance_new
    treeStrategy.assertValid()

    // Cache input
    if (input.getStorageLevel == StorageLevel.NONE) {
      input.persist(StorageLevel.MEMORY_AND_DISK)
    }

    timer.stop("init")

    Logger.getRootLogger().error("##########")
    Logger.getRootLogger().error("Building tree 0")
    Logger.getRootLogger().error("##########")
    var data = input

    // Initialize tree
    timer.start("building tree 0")
    val firstTreeModel = new DecisionTree_new(treeStrategy).run(data)
    baseLearners(0) = firstTreeModel
    baseLearnerWeights(0) = 1.0
    val startingModel = new GradientBoostedTreesModel(Regression, Array(firstTreeModel), Array(1.0))
    Logger.getRootLogger().error("error of gbt = " + loss.computeError(startingModel, input))
    // Note: A model of type regression is used since we require raw prediction
    timer.stop("building tree 0")

    // psuedo-residual for second iteration
    data = input.map(point => LabeledPoint(loss.gradient(startingModel, point),
      point.features))

    var m = 1
    while (m < numIterations) {
      timer.start(s"building tree $m")
      Logger.getRootLogger().error("###################################################")
      Logger.getRootLogger().error("Gradient boosting tree iteration " + m)
      Logger.getRootLogger().error("###################################################")
      val model = new DecisionTree_new(treeStrategy).run(data)
      timer.stop(s"building tree $m")
      // Create partial model
      baseLearners(m) = model
      // Note: The setting of baseLearnerWeights is incorrect for losses other than SquaredError.
      //       Technically, the weight should be optimized for the particular loss.
      //       However, the behavior should be reasonable, though not optimal.
      baseLearnerWeights(m) = learningRate
      // Note: A model of type regression is used since we require raw prediction
      val partialModel = new GradientBoostedTreesModel(
        Regression, baseLearners.slice(0, m + 1), baseLearnerWeights.slice(0, m + 1))
      Logger.getRootLogger().error("error of gbt = " + loss.computeError(partialModel, input))
      // Update data with pseudo-residuals
      data = input.map(point => LabeledPoint(-loss.gradient(partialModel, point),
        point.features))
      m += 1
    }

    timer.stop("total")

    logInfo("Internal timing for DecisionTree:")
    logInfo(s"$timer")

    new GradientBoostedTreesModel(
      boostingStrategy.treeStrategy.algo, baseLearners, baseLearnerWeights)
  }
}
