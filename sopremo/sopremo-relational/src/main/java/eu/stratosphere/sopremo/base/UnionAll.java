package eu.stratosphere.sopremo.base;

import java.util.List;

import org.codehaus.jackson.JsonNode;

import eu.stratosphere.pact.common.contract.CoGroupContract;
import eu.stratosphere.pact.common.contract.Contract;
import eu.stratosphere.pact.common.plan.PactModule;
import eu.stratosphere.sopremo.ElementaryOperator;
import eu.stratosphere.sopremo.EvaluationContext;
import eu.stratosphere.sopremo.Operator;
import eu.stratosphere.sopremo.StreamArrayNode;
import eu.stratosphere.sopremo.pact.JsonCollector;
import eu.stratosphere.sopremo.pact.PactJsonObject;
import eu.stratosphere.sopremo.pact.SopremoCoGroup;
import eu.stratosphere.sopremo.pact.SopremoUtil;

public class UnionAll extends ElementaryOperator {
	/**
	 * 
	 */
	private static final long serialVersionUID = 8160253166221264064L;

	public UnionAll(List<Operator> inputs) {
		super(inputs);
	}

	public UnionAll(Operator... inputs) {
		super(inputs);
	}

	@Override
	public PactModule asPactModule(EvaluationContext context) {
		int numInputs = this.getInputOperators().size();
		PactModule module = new PactModule(this.toString(), numInputs, 1);

		Contract leftInput = module.getInput(0);
		for (int index = 1; index < numInputs; index++) {

			Contract rightInput = module.getInput(index);
			CoGroupContract<PactJsonObject.Key, PactJsonObject, PactJsonObject, PactJsonObject.Key, PactJsonObject> union =
				new CoGroupContract<PactJsonObject.Key, PactJsonObject, PactJsonObject, PactJsonObject.Key, PactJsonObject>(
					TwoInputUnion.class);
			union.setFirstInput(leftInput);
			union.setSecondInput(rightInput);

			SopremoUtil.setContext(union.getStubParameters(), context);
			leftInput = union;
		}

		module.getOutput(0).setInput(leftInput);

		return module;
	}

	// TODO: replace with efficient union operator
	public static class TwoInputUnion extends
			SopremoCoGroup<PactJsonObject.Key, PactJsonObject, PactJsonObject, PactJsonObject.Key, PactJsonObject> {
		@Override
		protected void coGroup(JsonNode key, StreamArrayNode values1, StreamArrayNode values2, JsonCollector out) {
			for (JsonNode value : values1)
				out.collect(key, value);
			for (JsonNode value : values2)
				out.collect(key, value);
		}
	}
}
