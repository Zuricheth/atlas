<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue?: string | number | null
  type?: string
  placeholder?: string
  label?: string
  hint?: string
  error?: string
  disabled?: boolean
  multiline?: boolean
  rows?: number
  block?: boolean
  size?: 'sm' | 'md'
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const inputValue = computed({
  get: () => (props.modelValue == null ? '' : String(props.modelValue)),
  set: (v: string) => emit('update:modelValue', v),
})
</script>

<template>
  <label :class="['a-field', block && 'a-field--block', error && 'a-field--error']">
    <span v-if="label" class="a-field__label">{{ label }}</span>
    <textarea
      v-if="multiline"
      v-model="inputValue"
      :rows="rows || 4"
      :placeholder="placeholder"
      :disabled="disabled"
      class="a-field__control a-field__control--textarea"
      :class="`a-field__control--${size || 'md'}`"
    />
    <input
      v-else
      v-model="inputValue"
      :type="type || 'text'"
      :placeholder="placeholder"
      :disabled="disabled"
      class="a-field__control"
      :class="`a-field__control--${size || 'md'}`"
    />
    <small v-if="error" class="a-field__msg a-field__msg--error">{{ error }}</small>
    <small v-else-if="hint" class="a-field__msg">{{ hint }}</small>
  </label>
</template>

<style scoped>
.a-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.a-field--block { width: 100%; }

.a-field__label {
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--muted);
}

.a-field__control {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--panel);
  color: var(--text);
  outline: none;
  transition: border-color var(--t) var(--ease-out),
              box-shadow var(--t) var(--ease-out),
              background var(--t) var(--ease-out);
  font: inherit;
}
.a-field__control--sm { padding: 6px 10px; font-size: var(--fs-sm); }
.a-field__control--md { padding: 10px 12px; font-size: var(--fs-base); }

.a-field__control--textarea {
  line-height: 1.65;
  resize: vertical;
  min-height: 80px;
}

.a-field__control:hover:not(:focus):not(:disabled) {
  border-color: var(--border-strong);
}

.a-field__control:focus {
  border-color: var(--primary);
  box-shadow: var(--ring);
}

.a-field__control:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  background: var(--panel-soft);
}

.a-field--error .a-field__control {
  border-color: var(--danger);
}
.a-field--error .a-field__control:focus {
  box-shadow: var(--ring-danger);
}

.a-field__msg {
  font-size: var(--fs-xs);
  color: var(--muted);
}
.a-field__msg--error { color: var(--danger); }
</style>
