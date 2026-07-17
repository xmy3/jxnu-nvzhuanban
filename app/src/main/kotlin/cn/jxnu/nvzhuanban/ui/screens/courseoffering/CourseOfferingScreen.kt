package cn.jxnu.nvzhuanban.ui.screens.courseoffering

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.CourseOfferingForm
import cn.jxnu.nvzhuanban.data.model.CourseOfferingQuery
import cn.jxnu.nvzhuanban.data.model.CourseOfferingTable
import cn.jxnu.nvzhuanban.data.model.FormOption
import cn.jxnu.nvzhuanban.ui.components.BackNavigationIcon
import cn.jxnu.nvzhuanban.ui.components.EmptyState
import cn.jxnu.nvzhuanban.ui.components.ErrorState
import cn.jxnu.nvzhuanban.ui.components.LoadingState
import cn.jxnu.nvzhuanban.ui.components.StateScaffold
import cn.jxnu.nvzhuanban.ui.theme.AppShape

/** 「不限」在教务网表单里的字面 value（四个下拉共用）。 */
private const val VALUE_UNRESTRICTED = "不限"

/**
 * 开课查询页：按 学期 / 学院 / 星期 / 节次 / 教室号 / 课程名 / 教师姓名 组合检索全校开课。
 *
 * 结果表列由教务网服务端决定（表头驱动渲染，见 [cn.jxnu.nvzhuanban.data.model.CourseOfferingTable]）。
 * 「查询」要求至少给一个**收敛性**条件（学院≠不限，或任一文本框非空）——全不限会把全校
 * 一学期的开课整表拉回来，响应大且对教务网不友好。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseOfferingScreen(
    onBack: () -> Unit,
    viewModel: CourseOfferingViewModel = viewModel(),
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val resultState by viewModel.resultState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.course_offering_title)) },
                navigationIcon = { BackNavigationIcon(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        StateScaffold(
            state = formState,
            modifier = Modifier.padding(padding),
            onRetry = viewModel::loadForm,
        ) { form ->
            FormAndResults(
                form = form,
                resultState = resultState,
                onSearch = viewModel::search,
                onRetrySearch = viewModel::retrySearch,
            )
        }
    }
}

@Composable
private fun FormAndResults(
    form: CourseOfferingForm,
    resultState: CourseOfferingResultState,
    onSearch: (CourseOfferingQuery) -> Unit,
    onRetrySearch: () -> Unit,
) {
    // 下拉选中值都存 value（不是 label）：value 是教务网回传口径，也是唯一稳定键。
    // null = 未选过 → 生效值取首项（学期首项即当前可查的最新学期）。
    var semesterValue by rememberSaveable { mutableStateOf<String?>(null) }
    var collegeValue by rememberSaveable { mutableStateOf(VALUE_UNRESTRICTED) }
    var weekValue by rememberSaveable { mutableStateOf(VALUE_UNRESTRICTED) }
    var sectionValue by rememberSaveable { mutableStateOf(VALUE_UNRESTRICTED) }
    var classroom by rememberSaveable { mutableStateOf("") }
    var courseName by rememberSaveable { mutableStateOf("") }
    var teacherName by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    val effectiveSemester = semesterValue ?: form.semesters.firstOrNull()?.value.orEmpty()
    // 至少一个收敛性条件：学院、或任一文本条件。星期/节次单独选仍是全校范围，不算数。
    val hasCriteria = collegeValue != VALUE_UNRESTRICTED ||
        classroom.isNotBlank() || courseName.isNotBlank() || teacherName.isNotBlank()

    val submit: () -> Unit = {
        if (hasCriteria) {
            keyboard?.hide()
            onSearch(
                CourseOfferingQuery(
                    semesterValue = effectiveSemester,
                    collegeValue = collegeValue,
                    weekValue = weekValue,
                    sectionValue = sectionValue,
                    classroom = classroom.trim(),
                    courseName = courseName.trim(),
                    teacherName = teacherName.trim(),
                ),
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = AppShape.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField(
                        label = "学期",
                        options = form.semesters,
                        selectedValue = effectiveSemester,
                        onSelect = { semesterValue = it.value },
                        modifier = Modifier.weight(1f),
                    )
                    DropdownField(
                        label = "学院",
                        options = form.colleges,
                        selectedValue = collegeValue,
                        onSelect = { collegeValue = it.value },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField(
                        label = "星期",
                        options = form.weeks,
                        selectedValue = weekValue,
                        onSelect = { weekValue = it.value },
                        modifier = Modifier.weight(1f),
                    )
                    DropdownField(
                        label = "节次",
                        options = form.sections,
                        selectedValue = sectionValue,
                        onSelect = { sectionValue = it.value },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = { Text("课程名称（可模糊）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = classroom,
                        onValueChange = { classroom = it },
                        label = { Text("教室号") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = teacherName,
                        onValueChange = { teacherName = it },
                        label = { Text("教师姓名") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = submit,
                    enabled = hasCriteria && resultState !is CourseOfferingResultState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (resultState is CourseOfferingResultState.Loading) "查询中…" else "查询")
                }
                if (!hasCriteria) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "请先选择学院，或填写 课程 / 教室 / 教师 任一条件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (val s = resultState) {
                is CourseOfferingResultState.Initial -> EmptyState(
                    message = "可查任意课程在哪上、某老师的课表、某教室的占用",
                    icon = Icons.Outlined.Search,
                )
                is CourseOfferingResultState.Loading -> LoadingState()
                is CourseOfferingResultState.Error -> ErrorState(
                    message = s.message,
                    onRetry = onRetrySearch,
                )
                is CourseOfferingResultState.Success -> ResultList(table = s.table)
            }
        }
    }
}

@Composable
private fun ResultList(table: CourseOfferingTable) {
    if (table.isEmpty) {
        EmptyState(
            message = table.message ?: "没有找到符合条件的开课记录",
            icon = Icons.Outlined.Search,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "__count__") {
            Text(
                text = "共 ${table.rows.size} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        items(table.rows) { row ->
            ResultCard(columns = table.columns, row = row)
        }    }
}

/**
 * 结果卡片标题优先取「课程名」类列。实测 gvContent 真实列名是「课程名称标识」，
 * 故用**子串**匹配（不是精确相等）：列名里含「课程」且不是「课程讨论区」这种明显非课名列即可。
 * 一个都没命中时 titleIndex 为 -1，标题退回首格但正文会跳过该格（不重复渲染）。
 */
private val TITLE_HINTS = listOf("课程名称标识", "课程名称", "课程名", "课程")
private val TITLE_EXCLUDE = listOf("课程讨论区")

private fun pickTitleIndex(columns: List<String>, row: List<String>): Int {
    for (hint in TITLE_HINTS) {
        val idx = columns.indices.firstOrNull { i ->
            val c = columns[i]
            c.contains(hint) && c !in TITLE_EXCLUDE && row.getOrNull(i)?.isNotBlank() == true
        }
        if (idx != null) return idx
    }
    return -1
}

@Composable
private fun ResultCard(columns: List<String>, row: List<String>) {
    val titleIndex = pickTitleIndex(columns, row)
    // titleIndex<0（无表头 / 没有课名列）时退回首格当标题；此时正文渲染要跳过第 0 格避免重复。
    val fallbackTitle = titleIndex < 0
    val title = if (titleIndex >= 0) row[titleIndex] else row.firstOrNull().orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.listItem,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(6.dp))
            if (columns.isEmpty()) {
                // 无表头（服务端未按 th 渲染）：逐格平铺。退回首格当标题时跳过第 0 格避免重复。
                val body = if (fallbackTitle) row.drop(1) else row
                body.filter { it.isNotBlank() }.forEach { cell ->
                    Text(text = cell, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                columns.forEachIndexed { i, col ->
                    // 跳过已用作标题的那一格：命中的课名列，或回退时的第 0 格
                    if (i == titleIndex || (fallbackTitle && i == 0)) return@forEachIndexed
                    val value = row.getOrNull(i).orEmpty()
                    if (value.isBlank()) return@forEachIndexed
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(
                            text = col,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(76.dp),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * readOnly OutlinedTextField + 透明点击层 + [DropdownMenu] 的简易下拉。
 * 不用 ExposedDropdownMenuBox：其 menuAnchor API 在近几个 material3 版本里反复改名，
 * 这里的组合只依赖最稳定的原语。
 */
@Composable
private fun DropdownField(
    label: String,
    options: List<FormOption>,
    selectedValue: String?,
    onSelect: (FormOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label
        ?: options.firstOrNull()?.label.orEmpty()
    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // readOnly TextField 自己会吞点击，盖一层透明可点区域接管展开
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
