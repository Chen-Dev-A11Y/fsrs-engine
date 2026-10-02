<template>
  <router-view />
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useBackendStore } from '@/stores/backend'

const backend = useBackendStore()
onMounted(() => backend.bind())
</script>
