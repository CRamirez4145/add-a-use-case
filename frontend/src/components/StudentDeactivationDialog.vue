<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="setVisible"
    title="Deactivate student"
    width="560px"
    data-cy="deactivation-dialog"
    :before-close="beforeClose"
    :close-on-click-modal="!pending"
    :close-on-press-escape="!pending"
    :show-close="!pending"
  >
    <div data-cy="deactivation-warning">
      <p>Deactivate {{ student.firstName }} {{ student.lastName }}?</p>
      <p>
        The student will immediately lose access to the system, including access with an existing
        token.
      </p>
      <p>
        Team membership, the account, submitted weekly activity reports, and submitted and received
        peer evaluations are retained. Historical scores, comments, hours, and totals remain
        unchanged. Reports will show the current status as Deactivated.
      </p>
      <p>
        The student will no longer owe weekly activity reports or peer evaluations. Active teammates
        will no longer need to evaluate this student. New peer evaluations and edits targeting this
        student will be blocked.
      </p>
      <p>
        The account can be reactivated in the future. Deactivation does not delete the student or
        submitted work.
      </p>
      <p>You may cancel before confirming. After confirmation, wait for the request to finish.</p>
    </div>
    <template #footer>
      <el-button data-cy="deactivation-cancel" :disabled="pending" @click="setVisible(false)"
        >Cancel</el-button
      >
      <el-button
        data-cy="deactivation-confirm"
        type="danger"
        :loading="pending"
        :disabled="pending || student.id == null || student.sectionId == null"
        @click="confirm"
        >Confirm deactivation</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deactivateStudent } from '@/apis/student'
import type { Student } from '@/apis/student/types'

const props = defineProps<{ modelValue: boolean; student: Student }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  completed: [student: Student]
}>()
const pending = ref(false)

function setVisible(value: boolean) {
  if (!pending.value) emit('update:modelValue', value)
}
function beforeClose(done: () => void) {
  if (!pending.value) done()
}
async function confirm() {
  if (pending.value || props.student.id == null || props.student.sectionId == null) return
  pending.value = true
  try {
    const result = await deactivateStudent(props.student.sectionId, props.student.id)
    emit('completed', result.data.student)
    emit('update:modelValue', false)
    if (result.data.outcome === 'DEACTIVATED') ElMessage.success(result.message)
    else ElMessage.info(result.message)
  } catch {
    // The shared request client displays server/network errors and performs required navigation.
    // Leave the dialog available for an explicit retry if the user remains on this page.
  } finally {
    pending.value = false
  }
}
</script>
