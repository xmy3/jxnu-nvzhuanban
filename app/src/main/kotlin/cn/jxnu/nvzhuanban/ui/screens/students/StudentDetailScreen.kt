package cn.jxnu.nvzhuanban.ui.screens.students

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jxnu.nvzhuanban.R
import cn.jxnu.nvzhuanban.data.model.StudentInfo
import cn.jxnu.nvzhuanban.data.network.JxnuUrls
import cn.jxnu.nvzhuanban.ui.components.RemoteJwcImage
import cn.jxnu.nvzhuanban.ui.components.StateScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentDetailScreen(
    userNum: String,
    name: String,
    department: String,
    onBack: () -> Unit,
    onOpenSchedule: () -> Unit,
    viewModel: StudentDetailViewModel = viewModel(factory = StudentDetailViewModel.factory(userNum, name)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { stringResource(R.string.student_detail_title_fallback) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        StateScaffold(
            state = state,
            modifier = Modifier.padding(padding),
            onRetry = viewModel::load,
        ) { info ->
            StudentDetailBody(
                userNum = userNum,
                fallbackName = name,
                department = department,
                info = info,
                onOpenSchedule = onOpenSchedule,
            )
        }
    }
}

@Composable
private fun StudentDetailBody(
    userNum: String,
    fallbackName: String,
    department: String,
    info: StudentInfo,
    onOpenSchedule: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StudentHeader(
                userNum = userNum,
                info = info,
                fallbackName = fallbackName,
                department = department,
            )
        }
        item {
            InfoCard(
                rows = listOfNotNull(
                    info.studentId.takeIf { it.isNotBlank() }?.let { "学号" to it },
                    info.className.takeIf { it.isNotBlank() }?.let { "班级" to it },
                    department.takeIf { it.isNotBlank() }?.let { "所在单位" to it },
                ),
            )
        }
        item {
            Button(
                onClick = onOpenSchedule,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.student_detail_open_schedule))
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun StudentHeader(
    userNum: String,
    info: StudentInfo,
    fallbackName: String,
    department: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    ),
                ),
            )
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 学生头像：用户主动检索，逐人加载，不走 AvatarPrefs 全局开关
            RemoteJwcImage(
                url = JxnuUrls.studentPhotoUrl(userNum),
                contentDescription = stringResource(R.string.cd_avatar),
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
                fallback = {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                },
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.name.ifBlank { fallbackName },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                val sub = listOfNotNull(
                    info.gender.takeIf { it.isNotBlank() },
                    department.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
