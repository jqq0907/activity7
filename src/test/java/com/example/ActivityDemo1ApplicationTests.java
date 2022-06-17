package com.example;

import com.example.config.SecurityUtil;
import org.activiti.api.runtime.shared.query.Page;
import org.activiti.api.runtime.shared.query.Pageable;
import org.activiti.api.task.model.Task;
import org.activiti.api.task.model.builders.TaskPayloadBuilder;
import org.activiti.api.task.runtime.TaskRuntime;
import org.activiti.engine.ProcessEngine;
import org.activiti.engine.ProcessEngines;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

@SpringBootTest
class ActivityDemo1ApplicationTests {
	@Resource
	TaskRuntime taskRuntime;
	@Resource
	RepositoryService repositoryService;
	@Resource
	RuntimeService runtimeService;
	@Resource
	SecurityUtil securityUtil;
	private Logger logger = LoggerFactory.getLogger(ActivityDemo1Application.class);

	@Test
	void contextLoads() {
		Deployment deploy = repositoryService.createDeployment().addClasspathResource("processes/1.bpmn20.xml").name("请假流程").deploy();
		securityUtil.logInAs("bob");
		Task task = taskRuntime.create(TaskPayloadBuilder.create()
				.withName("first task")
				.withDescription("第一个任务")
				.withAssignee("bob")
				.withCandidateGroup("group1")
				.withPriority(10)
				.build());
		securityUtil.logInAs("other");

		// Let's get all my tasks (as 'other' user)
		logger.info("> Getting all the tasks");
		Page<Task> tasks = taskRuntime.tasks(Pageable.of(0, 10));

		// No tasks are returned
		logger.info(">  Other cannot see the task: " + tasks.getTotalItems());


		// Now let's switch to a user that belongs to the activitiTeam
		securityUtil.logInAs("john");

		// Let's get 'john' tasks
		logger.info("> Getting all the tasks");
		tasks = taskRuntime.tasks(Pageable.of(0, 10));

		// 'john' can see and claim the task
		logger.info(">  john can see the task: " + tasks.getTotalItems());


		String availableTaskId = tasks.getContent().get(0).getId();

		// Let's claim the task, after the claim, nobody else can see the task and 'john' becomes the assignee
		logger.info("> Claiming the task");
		taskRuntime.claim(TaskPayloadBuilder.claim().withTaskId(availableTaskId).build());


		// Let's complete the task
		logger.info("> Completing the task");
		taskRuntime.complete(TaskPayloadBuilder.complete().withTaskId(availableTaskId).build());
	}
	@Test
	void test1() {
		Deployment deploy = repositoryService.createDeployment()
				.name("出差申请流程")
				.addClasspathResource("processes/1.bpmn20.xml")
				.key("11")
				.deploy();
		System.out.println();
	}
	@Test
	void test2() {
		Map<String,Object> variables = new HashMap<>();
		variables.put("variable01","aa");
		variables.put("variable02","bb");
		ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("a1", variables);
		runtimeService.setProcessInstanceName(processInstance.getId(), "这是一个流程实例，准备给审批人看");
		System.out.println();
	}

}
