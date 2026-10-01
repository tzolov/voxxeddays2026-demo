package com.example.demo.agent.capabilities;

import java.util.List;

import org.springaicommunity.agent.tools.SkillsTool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;

/**
 * The Skills capability — the analog of Pydantic AI harness's
 * {@code Skills('.agents/skills')}: it injects the on-demand {@link SkillsTool} into the
 * agent's ChatClient. The model initially sees only the skill names and descriptions and
 * loads a skill's full instructions only when it becomes relevant.
 */
public final class SkillsCapability<D> implements Capability<D> {

	private final List<Resource> skillsResources;

	public SkillsCapability(List<Resource> skillsResources) {
		this.skillsResources = List.copyOf(skillsResources);
	}

	@Override
	public String id() {
		return "skills";
	}

	@Override
	public void instrument(ChatClient.Builder chatClientBuilder) {
		chatClientBuilder.defaultTools(SkillsTool.builder().addSkillsResources(this.skillsResources).build());
	}

	@Override
	public String instructions(D deps) {
		return "Additional capability instructions are available as skills. "
				+ "Load the relevant skill before answering a question it covers.";
	}

}
