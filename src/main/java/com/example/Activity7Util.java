package com.example;

import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.sql.SqlExecutor;
import de.odysseus.el.ExpressionFactoryImpl;
import de.odysseus.el.util.SimpleContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.activiti.api.process.runtime.ProcessRuntime;
import org.activiti.api.runtime.shared.query.Page;
import org.activiti.api.task.runtime.TaskRuntime;
import org.activiti.bpmn.model.*;
import org.activiti.engine.*;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricProcessInstanceQuery;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.Execution;
import org.activiti.engine.runtime.ExecutionQuery;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Comment;
import org.activiti.engine.task.Task;
import org.activiti.engine.task.TaskQuery;
import org.activiti.image.ProcessDiagramGenerator;
import org.activiti.image.impl.DefaultProcessDiagramGenerator;
import org.activiti.runtime.api.query.impl.PageImpl;
import org.springframework.data.relational.core.sql.In;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import javax.el.ExpressionFactory;
import javax.el.ValueExpression;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * @author jiangqiangqiang
 * @description:
 * @date 2022/7/2 5:36 PM
 */
@Component
@Slf4j
@AllArgsConstructor
public class Activity7Util {
	private final RepositoryService repositoryService;
	private final RuntimeService runtimeService;
	private final ProcessRuntime processRuntime;
	private final TaskService taskService;
	private final HistoryService historyService;
	private final TaskRuntime taskRuntime;

	/**
	 * 流程部署
	 *
	 * @param name          流程名称
	 * @param deploymentKey 流程key
	 * @param path          流程资源路径
	 * @return /
	 */
	public Deployment deploy(String name, String deploymentKey, String path) {
		List<Deployment> deployments = queryDeploymentByKey(deploymentKey);
		Assert.isTrue(deployments.isEmpty(), "重复部署");
		return repositoryService.createDeployment()
				.name(name)
				.key(deploymentKey)
				.addClasspathResource(path)
				.deploy();
	}

	/**
	 * 取消部署
	 *
	 * @param deploymentKey 流程key
	 * @param cascade       是否级联删除所有关联的流程及其历史记录
	 */
	public void deleteDeploy(String deploymentKey, Boolean cascade) {
		List<Deployment> deployments = queryDeploymentByKey(deploymentKey);
		for (Deployment deployment : deployments) {
			repositoryService.deleteDeployment(deployment.getId(), cascade);
		}
	}

	/**
	 * 获取流程定义
	 *
	 * @param startNum 分页开始下标，0
	 * @param endNum   分页结束下标
	 * @return /
	 */
	public Page<ProcessDefinition> processDefinitionPage(Integer startNum, Integer endNum) {
		List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery()
				.listPage(startNum, endNum);
		long count = repositoryService.createProcessDefinitionQuery().count();
		return new PageImpl<>(processDefinitions, (int) count);
	}

	/**
	 * 发起流程
	 *
	 * @param processDefinitionKey 流程定义key
	 * @param businessKey          关联的业务表id
	 * @param variables            预定义参数
	 * @return /
	 */
	public ProcessInstance startProcessInstance(String processDefinitionKey, String businessKey, Map<String, Object> variables) {
		return runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
	}

	/**
	 * 待批任务
	 *
	 * @param assignee    待批人(一般为用户id)
	 * @param firstResult 分页
	 * @param maxResult   分页
	 * @return /
	 */
	public Page<Map<String, Object>> getAssigneeTasks(String assignee, int firstResult, int maxResult) {
		TaskQuery taskQuery = taskService.createTaskQuery()
				.taskAssignee(assignee);
		long count = taskQuery.count();
		List<Task> tasks = taskQuery.listPage(firstResult, maxResult);

		ArrayList<Map<String, Object>> list = new ArrayList<>();
		for (Task task : tasks) {
			HashMap<String, Object> map = this.taskDetail(task.getId());
			list.add(map);
		}
		return new PageImpl<>(list, (int) count);
	}

	/**
	 * 用户发起的流程
	 *
	 * @param assignee    用户
	 * @param firstResult 分页
	 * @param maxResult   分页
	 * @param isFinished  是否完成
	 * @param before      在x时间节点钱
	 * @param after       在x时间节点后
	 * @return /
	 */
	public Page<Map<String, Object>> mindProcessInstance(String assignee, int firstResult, int maxResult, Boolean isFinished, Date before, Date after) {
		HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery().startedBy(assignee);
		if (isFinished != null) {
			if (isFinished) {
				query.finished();
			} else {
				query.unfinished();
			}
		}
		if (before != null) {
			query.startedBefore(before);
		}
		if (after != null) {
			query.startedAfter(after);
		}
		// 分页
		long count = query.count();
		List<HistoricProcessInstance> instanceList = query.listPage(firstResult, maxResult);
		ArrayList<Map<String, Object>> list = new ArrayList<>();
		for (HistoricProcessInstance historicProcessInstance : instanceList) {
			HashMap<String, Object> map = this.processInstanceDetail(historicProcessInstance.getId());
			list.add(map);
		}
		return new PageImpl<>(list, (int) count);
	}

	/**
	 * 获取当前任务节点的下一个任务节点(UserTask或者EndEvent),拿到值后判断类型后进行强转
	 * @param taskId 当前任务节点ID
	 * @return /
	 */
	public FlowElement getNextUserFlowElement(String taskId) {
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
		// 取得已提交的任务
		HistoricTaskInstance historicTaskInstance = historyService.createHistoricTaskInstanceQuery()
				.taskId(task.getId())
				.singleResult();
		// 获得流程定义
		ProcessDefinition processDefinition = repositoryService.getProcessDefinition(historicTaskInstance.getProcessDefinitionId());

		ExecutionQuery executionQuery = runtimeService.createExecutionQuery();
		Execution execution = executionQuery.executionId(historicTaskInstance.getExecutionId()).singleResult();
		String activityId = execution.getActivityId();
		UserTask userTask = null;
		while (true) {
			//根据活动节点获取当前的组件信息
			FlowNode flowNode = getFlowNode(processDefinition.getId(), activityId);
			//获取该节点之后的流向
			List<SequenceFlow> sequenceFlowListOutGoing = flowNode.getOutgoingFlows();
			if (sequenceFlowListOutGoing.size() > 1) {
				// 如果有1条以上的出线，表示有分支，需要判断分支的条件才能知道走哪个分支
				// 遍历节点的出线得到下个activityId
				activityId = getNextActivityId(execution.getId(), task.getProcessInstanceId(), sequenceFlowListOutGoing);
			} else if (sequenceFlowListOutGoing.size() == 1) {
				// 只有1条出线,直接取得下个节点
				SequenceFlow sequenceFlow = sequenceFlowListOutGoing.get(0);
				// 下个节点
				FlowElement flowElement = sequenceFlow.getTargetFlowElement();
				if (flowElement instanceof UserTask) {
					// 下个节点为UserTask时
					userTask = (UserTask) flowElement;
					System.out.println("下个任务为:" + userTask.getName());
					return userTask;
				} else if (flowElement instanceof ExclusiveGateway) {
					// 下个节点为排它网关时
					ExclusiveGateway exclusiveGateway = (ExclusiveGateway) flowElement;
					List<SequenceFlow> outgoingFlows = exclusiveGateway.getOutgoingFlows();
					// 遍历网关的出线得到下个activityId
					activityId = getNextActivityId(execution.getId(), task.getProcessInstanceId(), outgoingFlows);
					FlowNode flowNode_ = getFlowNode(processDefinition.getId(), activityId);
					if (flowNode_ instanceof UserTask) {
						return flowNode_;
					}
				} else if (flowElement instanceof EndEvent) {
					//下个节点是结束节点
					return flowElement;
				}
			} else {
				// 没有出线，则表明是结束节点
				return null;
			}
		}
	}

	public HashMap<String, Object> taskDetail(String taskId) {
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
		// 流程实例id
		String processInstanceId = task.getProcessInstanceId();
		// 流程实例
		ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
				.processInstanceId(processInstanceId)
				.singleResult();
		// 流程部署实例
		Deployment deployment = repositoryService.createDeploymentQuery()
				.processDefinitionKey(processInstance.getProcessDefinitionKey())
				.singleResult();

		// 组装数据
		HashMap<String, Object> map = new HashMap<>();
		// 流程部署名称
		map.put("deploymentName", deployment.getName());

		//任务id
		map.put("taskId", taskId);
		//任务名称
		map.put("taskName", task.getName());
		//任务描述
		map.put("taskDescription", task.getDescription());
		//任务的优先级【int】
		map.put("taskPriority", task.getPriority());
		//负责此任务的人员
		map.put("taskOwner", task.getOwner());
		//将此任务委派给的对象
		map.put("taskAssigneeUserId", task.getAssignee());
		//任务创建的时间
		map.put("taskCreateTime", task.getCreateTime());
		//任务截止日期
		map.put("taskDueDate", task.getDueDate());
		//任务类别
		map.put("taskCategory", task.getCategory());
		//任务的流程变量
		map.put("taskProcessVariables", task.getProcessVariables());
		//任务领取时间
		map.put("taskClaimTime", task.getClaimTime());

		//流程实例名称
		map.put("processInstanceName", processInstance.getName());
		//流程定义Key
		map.put("processDefinitionKey", processInstance.getProcessDefinitionKey());
		//流程实例关联的业务表ID
		map.put("processInstanceBusinessKey", processInstance.getBusinessKey());
		//流程实例是否被挂起
		map.put("processInstanceIsSuspended", processInstance.isSuspended());
		//流程实例变量
		map.put("processInstanceProcessVariables", processInstance.getProcessVariables());
		//流程实例描述
		map.put("processInstanceDescription", processInstance.getDescription());
		//流程实例开始的时间
		map.put("processInstanceStartTime", processInstance.getStartTime());
		//流程实例发起人的ID
		map.put("processInstanceStartUserId", processInstance.getStartUserId());
		return map;
	}

	/**
	 * 流程实例详情
	 *
	 * @param processInstanceId 流程实例ID
	 */
	public HashMap<String, Object> processInstanceDetail(String processInstanceId) {
		//历史流程实例
		HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
		Deployment deployment = repositoryService.createDeploymentQuery().processDefinitionKey(historicProcessInstance.getProcessDefinitionKey()).singleResult();
		//运行中流程实例
		ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(historicProcessInstance.getId()).singleResult();
		HashMap<String, Object> map = new HashMap<>();
		//流程部署名称
		map.put("deploymentName", deployment.getName());
		//流程实例ID
		map.put("processInstanceId", historicProcessInstance.getId());
		//流程实例ID
		map.put("processDefinitionKey", historicProcessInstance.getProcessDefinitionKey());
		//业务Key
		map.put("processInstanceBusinessKey", historicProcessInstance.getBusinessKey());
		//流程发起时间
		map.put("processInstanceStartTime", historicProcessInstance.getStartTime());
		//流程发起人
		map.put("processInstanceStartUserId", historicProcessInstance.getStartUserId());
		//流程是否结束
		if (processInstance == null) {
			map.put("isFinished", true);
		} else {
			map.put("isFinished", false);
			//查出当前审批节点
			HistoricTaskInstance historicTaskInstance = historyService.createHistoricTaskInstanceQuery().processInstanceId(processInstanceId).unfinished().singleResult();
			//当前节点审批人ID
			map.put("currentAssigneeUserId", historicTaskInstance.getAssignee());
			//任务名称
			map.put("currentTaskName", historicTaskInstance.getName());
		}
		return map;
	}

	public int dropActivityTables(Connection connection) throws SQLException {
		String sql = "DROP TABLE ACT_EVT_LOG,\n" +
				"ACT_GE_BYTEARRAY,\n" +
				"ACT_GE_PROPERTY,\n" +
				"ACT_HI_ACTINST,\n" +
				"ACT_HI_ATTACHMENT,\n" +
				"ACT_HI_COMMENT,\n" +
				"ACT_HI_DETAIL,\n" +
				"ACT_HI_IDENTITYLINK,\n" +
				"ACT_HI_PROCINST,\n" +
				"ACT_HI_TASKINST,\n" +
				"ACT_HI_VARINST,\n" +
				"ACT_PROCDEF_INFO,\n" +
				"ACT_RE_DEPLOYMENT,\n" +
				"ACT_RE_MODEL,\n" +
				"ACT_RE_PROCDEF,\n" +
				"ACT_RU_DEADLETTER_JOB,\n" +
				"ACT_RU_EVENT_SUBSCR,\n" +
				"ACT_RU_EXECUTION,\n" +
				"ACT_RU_IDENTITYLINK,\n" +
				"ACT_RU_INTEGRATION,\n" +
				"ACT_RU_JOB,\n" +
				"ACT_RU_SUSPENDED_JOB,\n" +
				"ACT_RU_TASK,\n" +
				"ACT_RU_TIMER_JOB,\n" +
				"ACT_RU_VARIABLE";
		int execute = SqlExecutor.execute(connection, sql);
		connection.close();
		return execute;
	}

	public List<Deployment> queryDeploymentByKey(String deploymentKey) {
		return repositoryService.createDeploymentQuery()
				.deploymentKey(deploymentKey)
				.list();
	}

	/**
	 * 任务处理(同意)
	 *
	 * @param taskId    任务ID
	 * @param comment   处理批注
	 * @param variables 预定义参数值
	 */
	public void disposeTask(String taskId, String comment, Map<String, Object> variables) {
		//如果没有指定下一步审批人，则不让处理
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
		FlowElement flowElement = this.getNextUserFlowElement(task.getId());
		if (flowElement instanceof UserTask) {
			UserTask userTask = (UserTask) flowElement;
			String assignee = userTask.getAssignee();
			Object o = variables.get(this.getVariableNameByExpression(assignee));
			Assert.isTrue(o != null && StrUtil.isNotBlank(o.toString()), "未指定下一步审批人");
		}
		taskService.addComment(taskId, task.getProcessInstanceId(), comment);
		taskService.complete(taskId, variables);

	}

	/**
	 * 终止任务,指向结束节点
	 *
	 * @param taskId  任务ID
	 * @param comment 任务批注
	 */
	public void endProcess(String taskId, String comment) {
		//  当前任务
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();

		BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());
		List<EndEvent> endEventList = bpmnModel.getMainProcess().findFlowElementsOfType(EndEvent.class);
		FlowNode endFlowNode = endEventList.get(0);
		FlowNode currentFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(task.getTaskDefinitionKey());

		//  临时保存当前活动的原始方向
		List<SequenceFlow> originalSequenceFlowList = new ArrayList<>(currentFlowNode.getOutgoingFlows());
		//  清理活动方向
		currentFlowNode.getOutgoingFlows().clear();

		//  建立新方向
		SequenceFlow newSequenceFlow = new SequenceFlow();
		newSequenceFlow.setId("newSequenceFlowId");
		newSequenceFlow.setSourceFlowElement(currentFlowNode);
		newSequenceFlow.setTargetFlowElement(endFlowNode);
		List<SequenceFlow> newSequenceFlowList = new ArrayList<>();
		newSequenceFlowList.add(newSequenceFlow);
		//  当前节点指向新的方向
		currentFlowNode.setOutgoingFlows(newSequenceFlowList);
		//任务批注
		taskService.addComment(taskId, task.getProcessInstanceId(), comment);
		//  完成当前任务
		taskService.complete(task.getId());
		//  可以不用恢复原始方向，不影响其它的流程
		currentFlowNode.setOutgoingFlows(originalSequenceFlowList);
	}


	/**
	 * 退回到上一节点
	 *
	 * @param task 当前任务
	 */
	public void backProcess(Task task, String comment) throws Exception {

		String processInstanceId = task.getProcessInstanceId();
		// 取得所有历史任务按时间降序排序
		List<HistoricTaskInstance> htiList = historyService.createHistoricTaskInstanceQuery()
				.processInstanceId(processInstanceId)
				.orderByTaskCreateTime()
				.desc()
				.list();
		int size = 2;
		if (ObjectUtils.isEmpty(htiList) || htiList.size() < size) {
			return;
		}
		// list里的第二条代表上一个任务
		HistoricTaskInstance lastTask = htiList.get(1);

		// list里第一条代表当前任务
		HistoricTaskInstance curTask = htiList.get(0);

		// 当前节点的executionId
		String curExecutionId = curTask.getExecutionId();


		// 上个节点的taskId
		String lastTaskId = lastTask.getId();
		// 上个节点的executionId
		String lastExecutionId = lastTask.getExecutionId();

		if (null == lastTaskId) {
			throw new Exception("LAST TASK IS NULL");
		}

		String processDefinitionId = lastTask.getProcessDefinitionId();
		BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);

		String lastActivityId = null;
		List<HistoricActivityInstance> haiFinishedList = historyService.createHistoricActivityInstanceQuery()
				.executionId(lastExecutionId).finished().list();

		for (HistoricActivityInstance hai : haiFinishedList) {
			if (lastTaskId.equals(hai.getTaskId())) {
				// 得到ActivityId，只有HistoricActivityInstance对象里才有此方法
				lastActivityId = hai.getActivityId();
				break;
			}
		}

		// 得到上个节点的信息
		FlowNode lastFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(lastActivityId);

		// 取得当前节点的信息
		Execution execution = runtimeService.createExecutionQuery().executionId(curExecutionId).singleResult();
		String curActivityId = execution.getActivityId();
		FlowNode curFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(curActivityId);

		//记录当前节点的原活动方向
		List<SequenceFlow> oriSequenceFlows = new ArrayList<>();
		oriSequenceFlows.addAll(curFlowNode.getOutgoingFlows());

		//清理活动方向
		curFlowNode.getOutgoingFlows().clear();

		//建立新方向
		List<SequenceFlow> newSequenceFlowList = new ArrayList<>();
		SequenceFlow newSequenceFlow = new SequenceFlow();
		newSequenceFlow.setId("newSequenceFlowId");
		newSequenceFlow.setSourceFlowElement(curFlowNode);
		newSequenceFlow.setTargetFlowElement(lastFlowNode);
		newSequenceFlowList.add(newSequenceFlow);
		curFlowNode.setOutgoingFlows(newSequenceFlowList);

		// 完成任务
		taskService.addComment(task.getId(), task.getProcessInstanceId(), comment);
		taskService.complete(task.getId());

		//恢复原方向
		curFlowNode.setOutgoingFlows(oriSequenceFlows);

		Task nextTask = taskService
				.createTaskQuery().processInstanceId(processInstanceId).singleResult();

		// 设置执行人
		if (nextTask != null) {
			taskService.setAssignee(nextTask.getId(), lastTask.getAssignee());
		}
	}

	/**
	 * 跳到最开始的任务节点（直接打回）
	 *
	 * @param task 当前任务
	 */
	public void jumpToStart(Task task, String comment) {
		ProcessEngine processEngine = ProcessEngines.getDefaultProcessEngine();
		HistoryService historyService = processEngine.getHistoryService();
		RepositoryService repositoryService = processEngine.getRepositoryService();
		TaskService taskService = processEngine.getTaskService();

		String processInstanceId = task.getProcessInstanceId();

		//  获取所有历史任务（按创建时间升序）
		List<HistoricTaskInstance> hisTaskList = historyService.createHistoricTaskInstanceQuery()
				.processInstanceId(processInstanceId)
				.orderByTaskCreateTime()
				.asc()
				.list();

		if (CollectionUtils.isEmpty(hisTaskList) || hisTaskList.size() < 2) {
			return;
		}

		//  第一个任务
		HistoricTaskInstance startTask = hisTaskList.get(0);
		//  当前任务
		HistoricTaskInstance currentTask = hisTaskList.get(hisTaskList.size() - 1);

		BpmnModel bpmnModel = repositoryService.getBpmnModel(task.getProcessDefinitionId());

		//  获取第一个活动节点
		FlowNode startFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(startTask.getTaskDefinitionKey());
		//  获取当前活动节点
		FlowNode currentFlowNode = (FlowNode) bpmnModel.getMainProcess().getFlowElement(currentTask.getTaskDefinitionKey());

		//  临时保存当前活动的原始方向
		List<SequenceFlow> originalSequenceFlowList = new ArrayList<>();
		originalSequenceFlowList.addAll(currentFlowNode.getOutgoingFlows());
		//  清理活动方向
		currentFlowNode.getOutgoingFlows().clear();

		//  建立新方向
		SequenceFlow newSequenceFlow = new SequenceFlow();
		newSequenceFlow.setId("newSequenceFlowId");
		newSequenceFlow.setSourceFlowElement(currentFlowNode);
		newSequenceFlow.setTargetFlowElement(startFlowNode);
		List<SequenceFlow> newSequenceFlowList = new ArrayList<>();
		newSequenceFlowList.add(newSequenceFlow);
		//  当前节点指向新的方向
		currentFlowNode.setOutgoingFlows(newSequenceFlowList);

		//  完成当前任务
		taskService.addComment(task.getId(), task.getProcessInstanceId(), comment);
		taskService.complete(task.getId());

		//  重新查询当前任务
		Task nextTask = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
		if (null != nextTask) {
			taskService.setAssignee(nextTask.getId(), startTask.getAssignee());
		}
		//  恢复原始方向
		currentFlowNode.setOutgoingFlows(originalSequenceFlowList);
	}

	/**
	 * 根据流程实例Id,获取实时流程图片
	 *
	 * @param processInstanceId 流程实例ID
	 * @param outputStream      输出流
	 * @param useCustomColor    true:用自定义的颜色（完成节点绿色，当前节点红色），default:用默认的颜色（红色）
	 */
	public void getFlowImgByInstanceId(String processInstanceId, OutputStream outputStream, boolean useCustomColor) {
		try {
			if (StringUtils.isEmpty(processInstanceId)) {
				log.error("processInstanceId is null");
				return;
			}
			// 获取历史流程实例
			HistoricProcessInstance historicProcessInstance = historyService
					.createHistoricProcessInstanceQuery()
					.processInstanceId(processInstanceId).singleResult();
			// 获取流程中已经执行的节点，按照执行先后顺序排序
			List<HistoricActivityInstance> historicActivityInstances = historyService
					.createHistoricActivityInstanceQuery()
					.processInstanceId(processInstanceId)
					.orderByHistoricActivityInstanceId()
					.asc().list();
			// 高亮已经执行流程节点ID集合
			List<String> highLightedActivitiIds = new ArrayList<>();
			int index = 1;
			for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
				if (useCustomColor) {
					//如果历史节点中有结束节点，则高亮结束节点
					if ("endEvent".equalsIgnoreCase(historicActivityInstance.getActivityType())) {
						highLightedActivitiIds.add(historicActivityInstance.getActivityId());
					}
					//如果没有结束时间，则是正在执行节点
					Date endTime = historicActivityInstance.getEndTime();
					if (endTime == null) {
						highLightedActivitiIds.add(historicActivityInstance.getActivityId() + "#");
					} else {
						// 已完成节点
						highLightedActivitiIds.add(historicActivityInstance.getActivityId());
					}
				} else {
					// 用默认颜色
					highLightedActivitiIds.add(historicActivityInstance.getActivityId());
				}

				index++;
			}

			ProcessDiagramGenerator processDiagramGenerator = null;
			if (useCustomColor) {
				// 使用自定义的程序图片生成器
				// processDiagramGenerator = new CustomProcessDiagramGenerator();

			} else {
				// 使用默认的程序图片生成器
				processDiagramGenerator = new DefaultProcessDiagramGenerator();
			}


			BpmnModel bpmnModel = repositoryService
					.getBpmnModel(historicProcessInstance.getProcessDefinitionId());
			// 高亮流程已发生流转的线id集合
			List<String> highLightedFlowIds = getHighLightedFlows(bpmnModel, historicActivityInstances);

			// 使用默认配置获得流程图表生成器，并生成追踪图片字符流
			InputStream imageStream = processDiagramGenerator.generateDiagram(bpmnModel,
					highLightedActivitiIds, highLightedFlowIds, "宋体",
					"微软雅黑", "黑体");

			// 输出图片内容
			Integer byteSize = 1024;
			byte[] b = new byte[byteSize];
			int len;
			while ((len = imageStream.read(b, 0, byteSize)) != -1) {
				outputStream.write(b, 0, len);
			}
		} catch (Exception e) {
			log.error("processInstanceId" + processInstanceId + "生成流程图失败，原因：" + e.getMessage(), e);
		}

	}

	/**
	 * 获取已经流转的线
	 *
	 * @param bpmnModel
	 * @param historicActivityInstances
	 * @return
	 */
	private List<String> getHighLightedFlows(BpmnModel bpmnModel,
	                                         List<HistoricActivityInstance> historicActivityInstances) {
		// 高亮流程已发生流转的线id集合
		List<String> highLightedFlowIds = new ArrayList<>();
		// 全部活动节点
		List<FlowNode> historicActivityNodes = new ArrayList<>();
		// 已完成的历史活动节点
		List<HistoricActivityInstance> finishedActivityInstances = new ArrayList<>();

		for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
			FlowNode flowNode = (FlowNode) bpmnModel.getMainProcess()
					.getFlowElement(historicActivityInstance.getActivityId(), true);
			historicActivityNodes.add(flowNode);
			if (historicActivityInstance.getEndTime() != null) {
				finishedActivityInstances.add(historicActivityInstance);
			}
		}

		FlowNode currentFlowNode = null;
		FlowNode targetFlowNode = null;
		// 遍历已完成的活动实例，从每个实例的outgoingFlows中找到已执行的
		for (HistoricActivityInstance currentActivityInstance : finishedActivityInstances) {
			// 获得当前活动对应的节点信息及outgoingFlows信息
			currentFlowNode = (FlowNode) bpmnModel.getMainProcess()
					.getFlowElement(currentActivityInstance.getActivityId(), true);
			List<SequenceFlow> sequenceFlows = currentFlowNode.getOutgoingFlows();

			/**
			 * 遍历outgoingFlows并找到已已流转的 满足如下条件认为已已流转：
			 * 1.当前节点是并行网关或兼容网关，则通过outgoingFlows能够在历史活动中找到的全部节点均为已流转
			 * 2.当前节点是以上两种类型之外的，通过outgoingFlows查找到的时间最早的流转节点视为有效流转
			 */
			if ("parallelGateway".equals(currentActivityInstance.getActivityType())
					|| "inclusiveGateway".equals(currentActivityInstance.getActivityType())) {
				// 遍历历史活动节点，找到匹配流程目标节点的
				for (SequenceFlow sequenceFlow : sequenceFlows) {
					targetFlowNode = (FlowNode) bpmnModel.getMainProcess()
							.getFlowElement(sequenceFlow.getTargetRef(), true);
					if (historicActivityNodes.contains(targetFlowNode)) {
						highLightedFlowIds.add(targetFlowNode.getId());
					}
				}
			} else {
				List<Map<String, Object>> tempMapList = new ArrayList<>();
				for (SequenceFlow sequenceFlow : sequenceFlows) {
					for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
						if (historicActivityInstance.getActivityId().equals(sequenceFlow.getTargetRef())) {
							Map<String, Object> map = new HashMap<>(16);
							map.put("highLightedFlowId", sequenceFlow.getId());
							map.put("highLightedFlowStartTime", historicActivityInstance.getStartTime().getTime());
							tempMapList.add(map);
						}
					}
				}

				if (!CollectionUtils.isEmpty(tempMapList)) {
					// 遍历匹配的集合，取得开始时间最早的一个
					long earliestStamp = 0L;
					String highLightedFlowId = null;
					for (Map<String, Object> map : tempMapList) {
						long highLightedFlowStartTime = Long.valueOf(map.get("highLightedFlowStartTime").toString());
						if (earliestStamp == 0 || earliestStamp >= highLightedFlowStartTime) {
							highLightedFlowId = map.get("highLightedFlowId").toString();
							earliestStamp = highLightedFlowStartTime;
						}
					}

					highLightedFlowIds.add(highLightedFlowId);
				}

			}

		}
		return highLightedFlowIds;
	}

	/**
	 * 获取流程节点的定义信息
	 *
	 * @param processDefinitionId
	 * @param flowElementId
	 * @return
	 */
	public FlowNode getFlowNode(String processDefinitionId, String flowElementId) {
		BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
		FlowElement flowElement = bpmnModel.getMainProcess().getFlowElement(flowElementId);
		return (FlowNode) flowElement;
	}


	/**
	 * 根据el表达式取得满足条件的下一个activityId
	 *
	 * @param executionId       执行实例ID
	 * @param processInstanceId 流程实例ID
	 * @param outgoingFlows     出线集合
	 * @return
	 */
	public String getNextActivityId(String executionId,
	                                String processInstanceId,
	                                List<SequenceFlow> outgoingFlows) {
		String activityId = null;
		// 遍历出线
		for (SequenceFlow outgoingFlow : outgoingFlows) {
			// 取得线上的条件
			String conditionExpression = outgoingFlow.getConditionExpression();
			// 取得所有变量
			Map<String, Object> variables = runtimeService.getVariables(executionId);
			HashMap<String, Object> variableNames = new HashMap<>();
			// 判断网关条件里是否包含变量名
			for (String s : variables.keySet()) {
				if (conditionExpression.contains(s)) {
					// 找到网关条件里的变量名
					variableNames.put(s, getVariableValue(s, processInstanceId));
				}
			}
			// 判断el表达式是否成立
			if (isCondition(conditionExpression, variableNames)) {
				// 取得目标节点
				FlowElement targetFlowElement = outgoingFlow.getTargetFlowElement();
				activityId = targetFlowElement.getId();
				continue;
			}
		}
		return activityId;
	}

	/**
	 * 取得流程变量的值
	 *
	 * @param variableName      变量名
	 * @param processInstanceId 流程实例Id
	 * @return
	 */
	public Object getVariableValue(String variableName, String processInstanceId) {
		Execution execution = runtimeService
				.createExecutionQuery().processInstanceId(processInstanceId).list().get(0);
		Object object = runtimeService.getVariable(execution.getId(), variableName);
		return object;
	}

	/**
	 * 根据key和value判断el表达式是否通过
	 *
	 * @param el            el表达式
	 * @param variableNames el表达式中的变量名和变量值
	 * @return bool
	 */
	public boolean isCondition(String el, Map<String, Object> variableNames) {
		ExpressionFactory factory = new ExpressionFactoryImpl();
		SimpleContext context = new SimpleContext();
		variableNames.forEach((k, v) -> {
			context.setVariable(k, factory.createValueExpression(v, ClassUtil.getClass(v)));
		});
		ValueExpression e = factory.createValueExpression(context, el, boolean.class);
		return (Boolean) e.getValue(context);
	}

	/**
	 * 通过EL表达式获取其中的变量名
	 *
	 * @param expression 表达式
	 * @return 变量名
	 */
	public String getVariableNameByExpression(String expression) {
		return expression.replace("${", "")
				.replace("}", "");
	}

	public List<Comment> getProcessComments(String processInstanceId) {
		List<Comment> historyCommnets = new ArrayList<>();

		List<HistoricActivityInstance> hais = historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId).activityType("userTask").list();
		for (HistoricActivityInstance hai : hais) {
			String historytaskId = hai.getTaskId();
			List<Comment> comments = taskService.getTaskComments(historytaskId);
			if (comments != null && comments.size() > 0) {
				historyCommnets.addAll(comments);
			}
		}
		return historyCommnets;
	}
}
