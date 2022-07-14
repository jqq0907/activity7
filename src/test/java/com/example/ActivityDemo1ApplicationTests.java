package com.example;

import cn.hutool.core.io.FileUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.CellData;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.FileUtils;
import com.example.config.SecurityUtil;
import org.activiti.api.runtime.shared.query.Page;
import org.activiti.api.runtime.shared.query.Pageable;
import org.activiti.api.task.model.Task;
import org.activiti.api.task.model.builders.TaskPayloadBuilder;
import org.activiti.api.task.model.payloads.CompleteTaskPayload;
import org.activiti.api.task.runtime.TaskRuntime;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.DeploymentBuilder;
import org.activiti.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StopWatch;

import javax.annotation.Resource;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
	@Resource
	TaskService taskService;
	private Logger logger = LoggerFactory.getLogger(ActivityDemo1Application.class);

	@Test
	void demo() throws IOException {
		List<String> list = new ArrayList<>();
		for (int i = 0; i < 7000; i++) {
			list.add("q"+i);
		}
		StopWatch stopWatch = new StopWatch();
		stopWatch.start("for循环");
		for (String s : list) {
			System.out.println(s);
		}
		stopWatch.stop();
		System.out.println(stopWatch.prettyPrint());
		stopWatch.start("stream循环");
		list.stream().forEach(System.out::println);
		stopWatch.stop();
		System.out.println(stopWatch.prettyPrint());
	}

	@Test
	void contextLoads() {
		Deployment deploy = repositoryService.createDeployment()
				.addClasspathResource("processes/1.bpmn20.xml")
				.name("请假流程")
				.deploy();
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
				.addClasspathResource("processes/1.bpmn20.xml")
				.name("请假流程")
				.deploy();
		System.out.println();
	}

	@Test
	void test2() {
		String businessKey = "1";
		Map<String, Object> variables = new HashMap<>();
		variables.put("userId", "bmjl");
		ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("holiday", variables);
		runtimeService.setProcessInstanceName(processInstance.getId(), "这是一个李三请假的流程实例，准备给审批人看");
		System.out.println();
	}

	@Test
	void test3() {
		List<org.activiti.engine.task.Task> list = taskService.createTaskQuery().taskAssignee("bmjl").list();
		for (org.activiti.engine.task.Task task : list) {
			taskRuntime.complete(new CompleteTaskPayload());
		}
		System.out.println();
	}

}
