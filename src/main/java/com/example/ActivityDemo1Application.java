package com.example;

import org.activiti.api.task.runtime.events.TaskAssignedEvent;
import org.activiti.api.task.runtime.events.TaskCompletedEvent;
import org.activiti.api.task.runtime.events.listener.TaskRuntimeEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportResource;

@SpringBootApplication(
		exclude = {org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
				org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration.class})
@ImportResource(locations = "classpath:mybatis.xml")
public class ActivityDemo1Application {
	private Logger logger = LoggerFactory.getLogger(ActivityDemo1Application.class);

	public static void main(String[] args) {
		SpringApplication.run(ActivityDemo1Application.class, args);
	}

	@Bean
	public TaskRuntimeEventListener<TaskAssignedEvent> taskAssignedListener() {
		return taskAssigned -> logger.info(">>> Task Assigned: '"
				+ taskAssigned.getEntity().getName() +
				"' We can send a notification to the assginee: " + taskAssigned.getEntity().getAssignee());
	}

	@Bean
	public TaskRuntimeEventListener<TaskCompletedEvent> taskCompletedListener() {
		return taskCompleted -> logger.info(">>> Task Completed: '"
				+ taskCompleted.getEntity().getName() +
				"' We can send a notification to the owner: " + taskCompleted.getEntity().getOwner());
	}

}
