package eu.stratosphere.sopremo.cleansing.record_linkage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

import eu.stratosphere.pact.common.type.KeyValuePair;
import eu.stratosphere.sopremo.CompactArrayNode;
import eu.stratosphere.sopremo.EvaluationContext;
import eu.stratosphere.sopremo.JsonStream;
import eu.stratosphere.sopremo.JsonUtil;
import eu.stratosphere.sopremo.expressions.ArrayCreation;
import eu.stratosphere.sopremo.expressions.EvaluationExpression;
import eu.stratosphere.sopremo.expressions.InputSelection;
import eu.stratosphere.sopremo.expressions.ObjectAccess;
import eu.stratosphere.sopremo.expressions.PathExpression;
import eu.stratosphere.sopremo.pact.PactJsonObject;
import eu.stratosphere.sopremo.pact.PactJsonObject.Key;
import eu.stratosphere.sopremo.testing.SopremoTestPlan;

/**
 * Tests {@link DisjunctPartitioning} {@link RecordLinkage} within one data source.
 * 
 * @author Arvid Heise
 */
public class DisjunctPratitioningRecordLinkageIntraSourceTest extends
		IntraSourceRecordLinkageTestBase<DisjunctPartitioning> {

	private double threshold;

	private EvaluationExpression projection;

	private boolean useId;

	private EvaluationExpression[] leftBlockingKeys, rightBlockingKeys;

	/**
	 * Initializes NaiveRecordLinkageInterSourceTest with the given parameter
	 * 
	 * @param threshold
	 * @param projection
	 * @param useId
	 * @param blockingKeys
	 */
	public DisjunctPratitioningRecordLinkageIntraSourceTest(double threshold, EvaluationExpression projection,
			boolean useId, String[][] blockingKeys) {
		this.threshold = threshold;
		this.projection = projection;
		this.useId = useId;

		this.leftBlockingKeys = new EvaluationExpression[blockingKeys[0].length];
		for (int index = 0; index < this.leftBlockingKeys.length; index++)
			this.leftBlockingKeys[index] = new ObjectAccess(blockingKeys[0][index]);
		this.rightBlockingKeys = new EvaluationExpression[blockingKeys[1].length];
		for (int index = 0; index < this.rightBlockingKeys.length; index++)
			this.rightBlockingKeys[index] = new ObjectAccess(blockingKeys[1][index]);
	}

	/**
	 * Returns the parameter combination under test.
	 * 
	 * @return the parameter combination
	 */
	@Parameters
	public static Collection<Object[]> getParameters() {
		EvaluationExpression[] projections = { null, getAggregativeProjection() };
		double[] thresholds = { 0.0, 0.5, 1.0 };
		boolean[] useIds = { false, true };

		ArrayList<Object[]> parameters = new ArrayList<Object[]>();
		for (EvaluationExpression projection : projections)
			for (double threshold : thresholds)
				for (String[][] combinedBlockingKeys : CombinedBlockingKeys)
					for (boolean useId : useIds)
						parameters.add(new Object[] { threshold, projection, useId, combinedBlockingKeys });

		return parameters;
	}

	/**
	 * Performs the naive record linkage in place and compares with the Pact code.
	 */
	@Test
	public void pactCodeShouldPerformLikeStandardImplementation() {
		EvaluationExpression similarityFunction = this.getSimilarityFunction();
		RecordLinkage recordLinkage = new RecordLinkage(
			new DisjunctPartitioning(this.leftBlockingKeys, this.leftBlockingKeys),
			similarityFunction, this.threshold, (JsonStream) null);
		SopremoTestPlan sopremoTestPlan = this.createTestPlan(recordLinkage, this.useId, this.projection);

		EvaluationExpression duplicateProjection = this.projection;
		if (duplicateProjection == null)
			duplicateProjection = new ArrayCreation(new PathExpression(new InputSelection(0),
				recordLinkage.getIdProjection(0)), new PathExpression(new InputSelection(1),
				recordLinkage.getIdProjection(0)));

		EvaluationContext context = sopremoTestPlan.getEvaluationContext();
		for (KeyValuePair<Key, PactJsonObject> left : sopremoTestPlan.getInput(0)) {
			boolean skipPairs = true;
			for (KeyValuePair<Key, PactJsonObject> right : sopremoTestPlan.getInput(0)) {
				if (left == right) {
					skipPairs = false;
					continue;
				} else if (skipPairs)
					continue;

				boolean inSameBlockingBin = false;
				for (int index = 0; index < this.leftBlockingKeys.length && !inSameBlockingBin; index++)
					if (this.leftBlockingKeys[index].evaluate(left.getValue().getValue(), context).equals(
						this.leftBlockingKeys[index].evaluate(right.getValue().getValue(), context)))
						inSameBlockingBin = true;
				if (!inSameBlockingBin)
					continue;

				CompactArrayNode pair = JsonUtil.asArray(left.getValue().getValue(), right.getValue().getValue());
				if (similarityFunction.evaluate(pair, context).getDoubleValue() > this.threshold)
					sopremoTestPlan.getExpectedOutput(0).add(
						new PactJsonObject(duplicateProjection.evaluate(pair, context)));
			}
		}

		try {
			sopremoTestPlan.run();
		} catch (AssertionError error) {
			throw new AssertionError(String.format("For test %s: %s", this, error.getMessage()));
		}
	}

	@Override
	public String toString() {
		return String.format("[threshold=%s, useId=%s, projection=%s, leftBlockingKeys=%s, rightBlockingKeys=%s]",
				this.threshold, this.useId, this.projection, Arrays.toString(this.leftBlockingKeys),
			Arrays.toString(this.rightBlockingKeys));
	}

}
