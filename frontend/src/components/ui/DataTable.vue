<script setup lang="ts" generic="T extends Record<string, unknown>">
import BaseSkeleton from './BaseSkeleton.vue'
import EmptyState from './EmptyState.vue'
import ErrorState from './ErrorState.vue'

/**
 * Data table with loading, error and empty states built in.
 *
 * Those three states are the whole reason this component exists: a screen should never
 * be able to render an empty table that means "still loading" or "request failed".
 * Columns are declared as data and each cell is a slot, so a table never grows its own
 * bespoke styling.
 */
export interface Column<Row> {
  key: string
  label: string
  /** Tailwind width class for the cell, e.g. `w-40`. */
  width?: string
  align?: 'left' | 'right' | 'center'
  sortable?: boolean
}

const props = withDefaults(
  defineProps<{
    columns: Column<T>[]
    rows: T[]
    rowKey: keyof T | ((row: T) => string | number)
    loading?: boolean
    error?: string | null
    emptyTitle?: string
    emptyDescription?: string
    emptyActionLabel?: string
    emptyIcon?: 'orders' | 'deliveries' | 'inventory' | 'notifications' | 'search'
    /** Turns each row into a keyboard reachable button, for drill-down tables. */
    clickable?: boolean
    caption?: string
  }>(),
  {
    loading: false,
    error: null,
    emptyTitle: 'Nothing to show',
    emptyDescription: '',
    emptyActionLabel: '',
    emptyIcon: 'search',
    clickable: false,
    caption: '',
  },
)

const emit = defineEmits<{ rowClick: [T]; emptyAction: []; retry: []; sort: [string] }>()

function keyFor(row: T): string | number {
  const { rowKey } = props
  return typeof rowKey === 'function' ? rowKey(row) : (row[rowKey] as string | number)
}

function alignClass(align: Column<T>['align']): string {
  return align === 'right' ? 'text-right' : align === 'center' ? 'text-center' : 'text-left'
}

function onRowKeydown(event: KeyboardEvent, row: T): void {
  if (props.clickable && (event.key === 'Enter' || event.key === ' ')) {
    event.preventDefault()
    emit('rowClick', row)
  }
}
</script>

<template>
  <div>
    <ErrorState
      v-if="error"
      :message="error"
      retry-label="Try again"
      @retry="emit('retry')"
    />

    <BaseSkeleton v-else-if="loading" :rows="Math.max(rows.length, 5)" />

    <EmptyState
      v-else-if="rows.length === 0"
      :title="emptyTitle"
      :description="emptyDescription"
      :action-label="emptyActionLabel"
      :icon="emptyIcon"
      @action="emit('emptyAction')"
    />

    <div v-else class="overflow-x-auto">
      <table class="w-full border-collapse text-left">
        <caption v-if="caption" class="sr-only">{{ caption }}</caption>
        <thead>
          <tr class="border-b border-edge dark:border-[#334155]">
            <th
              v-for="column in columns"
              :key="column.key"
              scope="col"
              class="whitespace-nowrap px-4 py-2.5 text-small font-semibold text-content-muted first:pl-5 last:pr-5 dark:text-[#94A3B8]"
              :class="[column.width, alignClass(column.align)]"
              :aria-sort="
                column.sortable ? ($attrs[`aria-sort-${column.key}`] as 'ascending' | 'descending' | 'none') : undefined
              "
            >
              <button
                v-if="column.sortable"
                type="button"
                class="inline-flex items-center gap-1 rounded transition-colors hover:text-content dark:hover:text-[#F8FAFC]"
                @click="emit('sort', column.key)"
              >
                {{ column.label }}
                <svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
                  <path d="m8 9 4-4 4 4M8 15l4 4 4-4" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>
              <template v-else>{{ column.label }}</template>
            </th>
          </tr>
        </thead>

        <tbody class="divide-y divide-edge dark:divide-[#1E293B]">
          <tr
            v-for="row in rows"
            :key="keyFor(row)"
            class="transition-colors duration-150"
            :class="clickable ? 'cursor-pointer hover:bg-surface-muted dark:hover:bg-[#1A2436]' : ''"
            :tabindex="clickable ? 0 : undefined"
            :role="clickable ? 'button' : undefined"
            @click="clickable && emit('rowClick', row)"
            @keydown="clickable && onRowKeydown($event, row)"
          >
            <td
              v-for="column in columns"
              :key="column.key"
              class="px-4 py-3 align-middle text-body text-content first:pl-5 last:pr-5 dark:text-[#F8FAFC]"
              :class="[column.width, alignClass(column.align)]"
            >
              <slot :name="`cell-${column.key}`" :row="row">{{ row[column.key] }}</slot>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>