package com.openminis.app.mibot.queue

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.mibot.design.ChipKind
import com.openminis.app.mibot.design.MibotButton
import com.openminis.app.mibot.design.MibotCard
import com.openminis.app.mibot.design.MibotScaffold
import com.openminis.app.mibot.design.MibotTokens
import com.openminis.app.mibot.design.MibotType
import com.openminis.app.mibot.design.StatusChip
import com.openminis.app.mibot.design.t
import com.openminis.app.mibot.design.tc
import com.openminis.app.ui.glass.BackdropMotion
import com.openminis.app.ui.glass.GlassPrefs
import com.openminis.app.ui.glass.ambientMotionAllowed
import java.text.DateFormat
import java.util.Date

private enum class QueueFilter { ALL, ACTIVE, WAITING, DONE }
private fun matches(filter: QueueFilter, job: AgentJob): Boolean = when (filter) {
    QueueFilter.ALL -> true
    QueueFilter.ACTIVE -> job.state == AgentJobState.RUNNING
    QueueFilter.WAITING -> job.state == AgentJobState.PENDING
    QueueFilter.DONE -> job.state == AgentJobState.DONE
}
private fun matches(filter: QueueFilter, run: TaskRun): Boolean = when (filter) {
    QueueFilter.ALL -> true
    // Queue metadata is not proof that an executor is alive.
    QueueFilter.ACTIVE -> false
    QueueFilter.WAITING -> run.state in setOf(TaskRunState.QUEUED, TaskRunState.HELD, TaskRunState.RUNNING)
    QueueFilter.DONE -> run.state == TaskRunState.DONE
}

/** Real job registry and queue records, integrated as an ordinary Mibot subpage. */
@Composable
fun TaskQueueScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onNewTask: () -> Unit,
    onScheduled: () -> Unit,
) {
    val context = LocalContext.current
    val queue = remember(context) { TaskRunQueue.get(context.applicationContext) }
    val runs by queue.runs.collectAsState()
    val jobs by AgentJobRegistry.jobs.collectAsState()
    val glass by remember(context) { GlassPrefs.flow(context) }.collectAsState()
    val duration = if (glass.motion == BackdropMotion.FULL && ambientMotionAllowed(context)) 220 else 0
    var filterName by rememberSaveable { mutableStateOf(QueueFilter.ALL.name) }
    val filter = QueueFilter.valueOf(filterName)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var stopId by remember { mutableStateOf<String?>(null) }
    val jobList = jobs.values.sortedByDescending { it.createdAtMs }
    val shownJobs = jobList.filter { matches(filter, it) }
    val shownRuns = runs.filter { matches(filter, it) }.sortedByDescending { it.createdAt }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    MibotScaffold(title = t("任务队列", "Task queue"), onBack = onBack, scroll = false, bottomPadding = 0.dp) {
        LazyColumn(
            Modifier.fillMaxSize().semantics { contentDescription = "Mibot Task Queue" },
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MibotButton(t("新建任务", "New task"), onNewTask,
                        Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Mibot New Task" })
                    MibotButton(t("定时任务", "Scheduled"), onScheduled,
                        Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Mibot Scheduled Tasks" }, tonal = true)
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(QueueFilter.entries) { option ->
                        val count = jobList.count { matches(option, it) } + runs.count { matches(option, it) }
                        val label = when (option) {
                            QueueFilter.ALL -> t("全部", "All")
                            QueueFilter.ACTIVE -> t("运行中", "Running")
                            QueueFilter.WAITING -> t("待处理", "Pending")
                            QueueFilter.DONE -> t("已完成", "Completed")
                        }
                        MibotButton("$label $count", { filterName = option.name },
                            Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mibot Task Filter ${option.name}" },
                            tonal = option != filter, compact = true)
                    }
                }
            }
            if (shownJobs.isEmpty() && shownRuns.isEmpty()) {
                item {
                    MibotCard(padding = PaddingValues(24.dp)) {
                        Text(t("暂无符合条件的任务", "No matching tasks"), fontSize = MibotType.Title,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        Text(t("运行中的子代理和本地队列会显示在这里。新建任务后，请在对话中输入目标并发送。",
                            "Agent jobs and local queue records appear here. Start a new task by sending a goal in chat."),
                            fontSize = MibotType.Body, lineHeight = MibotType.BodyLine,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (shownJobs.isNotEmpty()) {
                item { QueueSection(t("实际作业", "Agent jobs")) }
            }
            items(shownJobs, key = { "job:${it.id}" }) { job ->
                val key = "job:${job.id}"
                MibotCard(modifier = Modifier.animateContentSize(tween(duration)), padding = PaddingValues(16.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { contentDescription = "Mibot Task $key" }
                        .clickable { expanded = if (expanded == key) null else key }) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontSize = MibotType.TitleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(listOfNotNull(job.agentName, dateFormat.format(Date(job.createdAtMs))).joinToString(" · "),
                                fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        JobStatus(job.state)
                    }
                    if (expanded == key) {
                        Spacer(Modifier.height(12.dp))
                        val detail = job.resultText?.takeIf { it.isNotBlank() }
                            ?: job.summaryLine?.takeIf { it.isNotBlank() } ?: job.prompt
                        if (!detail.isNullOrBlank()) Text(detail.take(2400), fontSize = MibotType.Body,
                            lineHeight = MibotType.BodyLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            job.runSessionId?.takeIf { it.isNotBlank() }?.let { id ->
                                TextButton(onClick = { onOpenSession(id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(t("打开会话", "Open session"))
                                }
                            }
                            if (job.isActive) TextButton(onClick = { stopId = job.id }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(t("停止任务", "Stop job"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (job.runSessionId.isNullOrBlank()) Text(t("尚无关联会话", "No linked session yet"),
                            fontSize = MibotType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (shownRuns.isNotEmpty()) {
                item { QueueSection(t("本地队列记录", "Local queue records")) }
            }
            items(shownRuns, key = { "queue:${it.id}" }) { run ->
                val key = "queue:${run.id}"
                MibotCard(modifier = Modifier.animateContentSize(tween(duration)), padding = PaddingValues(16.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { contentDescription = "Mibot Task $key" }
                        .clickable { expanded = if (expanded == key) null else key }) {
                        Text(run.goal, modifier = Modifier.weight(1f), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, fontSize = MibotType.TitleSmall,
                            color = MaterialTheme.colorScheme.onSurface)
                        StatusChip(when (run.state) {
                            TaskRunState.QUEUED -> t("已排队", "Queued")
                            TaskRunState.HELD -> t("已暂存", "Held")
                            TaskRunState.RUNNING -> t("待核实", "Unverified")
                            TaskRunState.DONE -> t("已完成记录", "Recorded done")
                            TaskRunState.FAILED -> t("未完成", "Incomplete")
                        }, ChipKind.NEUTRAL)
                    }
                    Text(dateFormat.format(Date(run.createdAt)), fontSize = MibotType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expanded == key) {
                        Spacer(Modifier.height(12.dp))
                        Text(run.goal, fontSize = MibotType.Body, lineHeight = MibotType.BodyLine,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(t("本地状态不代表执行器仍在运行。继续会复制目标并打开新对话，请手动粘贴发送。",
                            "A saved state does not prove an executor is running. Continue copies the goal and opens a new chat; paste and send it there."),
                            modifier = Modifier.padding(top = 8.dp), fontSize = MibotType.Secondary,
                            lineHeight = MibotType.SecondaryLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Task goal", run.goal))
                            Toast.makeText(context, tc(context, "目标已复制，请粘贴后发送", "Goal copied; paste and send in chat"), Toast.LENGTH_SHORT).show()
                            onNewTask()
                        }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(t("复制目标并继续", "Copy goal & continue"))
                        }
                    }
                }
            }
            item {
                Text(t("作业状态来自本次运行的代理注册表；本地队列单独保存。这里不会把状态切换冒充任务执行。",
                    "Agent jobs report live registry state. Local queue records are stored separately; changing a record is not task execution."),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    fontSize = MibotType.Footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    stopId?.let { id ->
        val job = jobs[id]
        AlertDialog(onDismissRequest = { stopId = null },
            title = { Text(t("停止这个任务？", "Stop this job?")) },
            text = { Text(job?.title ?: t("任务已经结束", "Job already ended")) },
            confirmButton = {
                TextButton(onClick = {
                    stopId = null
                    if (AgentJobRegistry.jobs.value[id]?.isActive == true) {
                        AgentJobRegistry.cancel(id, "user-task-queue")
                    }
                }, enabled = job?.isActive == true, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(t("停止", "Stop"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { stopId = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(t("继续运行", "Keep running"))
            } },
        )
    }
}

@Composable
private fun QueueSection(title: String) {
    Text(title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        fontSize = MibotType.Section, fontWeight = MibotType.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun JobStatus(state: AgentJobState) {
    val label = when (state) {
        AgentJobState.PENDING -> t("待运行", "Pending")
        AgentJobState.RUNNING -> t("运行中", "Running")
        AgentJobState.DONE -> t("已完成", "Completed")
        AgentJobState.CANCELLED -> t("已停止", "Stopped")
        AgentJobState.FAILED -> t("失败", "Failed")
        AgentJobState.TIMEOUT -> t("已超时", "Timed out")
    }
    val kind = when (state) {
        AgentJobState.RUNNING -> ChipKind.ACCENT
        AgentJobState.DONE -> ChipKind.OK
        AgentJobState.FAILED, AgentJobState.TIMEOUT -> ChipKind.WARN
        else -> ChipKind.NEUTRAL
    }
    StatusChip(label, kind)
}
