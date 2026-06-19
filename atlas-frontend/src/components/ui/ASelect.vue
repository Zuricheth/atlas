<script setup lang="ts">
import { computed } from 'vue'

type Option = { value: string | number; label: string; disabled?: boolean }

const props = defineProps<{
  modelValue?: string | number | null
  options: Option[]
  label?: string
  placeholder?: string
  disabled?: boolean
  block?: boolean
  size?: 'sm' | 'md'
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string | number): void }>()

const value = computed({
  get: () => (props.modelValue == null ? '' : props.modelValue),
  set: (v: string | number) => emit('update:modelValue', v),
})
</script>

<template>
  <label :class="['a-select', block && 'a-select--block']">
    <span v-if="label" class="a-select__label">{{ label }}</span>
    <div class="a-select__wrap">
      <select
        v-model="value"
        :disabled="disabled"
        class="a-select__control"
        :class="`a-select__control--${size || 'md'}`"
      >
        <option v-if="placeholder" disabled value="">{{ placeholder }}</option>
        <option
          v-for="opt in options"
          :key="opt.value"
          :value="opt.value"
          :disabled="opt.disabled"
        >
          {{ opt.label }}
        </option>
      </select>
      <svg class="a-select__caret" viewBox="0 0 12 12" aria-hidden="true">
        <path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" fill="none" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>
  </label>
</template>

<style scoped>
.a-select { display: flex; flex-direction: column; gap: 6px; }
.a-select--block { width: 100%; }
.a-select__label { font-size: var(--fs-sm); font-weight: var(--fw-medium); color: var(--muted); }
.a-select__wrap { position: relative; }
.a-select__control {
  width: 100%;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--panel);
  color: var(--text);
  outline: none;
  appearance: none;
  -webkit-appearance: none;
  font: inherit;
  padding-right: 32px;
  cursor: pointer;
  transition: border-color var(--t) var(--ease-out), box-shadow var(--t) var(--ease-out);
}
.a-select__control--sm { padding: 6px 32px 6px 10px; font-size: var(--fs-sm); }
.a-select__control--md { padding: 10px 32px 10px 12px; font-size: var(--fs-base); }
.a-select__control:hover:not(:focus):not(:disabled) { border-color: var(--border-strong); }
.a-select__control:focus { border-color: var(--primary); box-shadow: var(--ring); }
.a-select__control:disabled { opacity: 0.6; cursor: not-allowed; background: var(--panel-soft); }
.a-select__caret {
  position: absolute;
  right: 10px;
  top: 50%;
  width: 12px;
  height: 12px;
  transform: translateY(-50%);
  color: var(--muted);
  pointer-events: none;
}
</style>
