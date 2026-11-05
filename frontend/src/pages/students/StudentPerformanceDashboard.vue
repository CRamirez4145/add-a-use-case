<template>
  <section v-if="student" data-cy="student-details">
    <h2>{{ student.firstName }} {{ student.lastName }}</h2>
    <el-tag data-cy="student-status" :type="student.enabled ? 'success' : 'info'">
      {{ student.enabled ? 'Active' : 'Deactivated' }}
    </el-tag>
    <p>{{ student.email }}</p>
    <p>Course section: {{ student.sectionName }}</p>
    <p>Team: {{ student.teamName || 'Unassigned' }}</p>
    <el-button
      data-cy="deactivation-open"
      type="danger"
      :disabled="!student.enabled"
      @click="warningVisible = true"
      >Deactivate student</el-button
    >
    <StudentDeactivationDialog
      v-model="warningVisible"
      :student="student"
      @completed="student = $event"
    />
    <el-divider />
    <el-button v-if="!showReports" data-cy="show-performance-reports" @click="showReports = true">
      Show performance reports
    </el-button>
    <template v-if="showReports && student.sectionId != null">
      <StudentActivities :studentId="studentId" />
      <el-divider />
      <StudentEvaluations :studentId="studentId" :sectionId="student.sectionId" />
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import StudentActivities from '../activities/admin/StudentActivities.vue'
import StudentEvaluations from '../evaluations/admin/StudentEvaluations.vue'

import { useRoute } from 'vue-router'
import { findStudentById } from '@/apis/student'
import type { Student } from '@/apis/student/types'
import StudentDeactivationDialog from '@/components/StudentDeactivationDialog.vue'

const route = useRoute()

const studentId: number = Number(route.params.studentId)
const student = ref<Student>()
const warningVisible = ref(false)
const showReports = ref(false)

onMounted(async () => {
  try {
    const result = await findStudentById(studentId)
    student.value = result.data
  } catch {
    // Shared request handling owns feedback and denied/not-found navigation.
  }
})
</script>

<style scoped></style>
