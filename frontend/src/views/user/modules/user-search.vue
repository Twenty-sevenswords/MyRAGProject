<script setup lang="ts">
import { enableStatusOptions } from '@/constants/common';

defineOptions({
  name: 'UserSearch'
});

const emit = defineEmits<{
  search: [];
}>();

const { formRef } = useNaiveForm();

const model = defineModel<Api.User.SearchParams>('model', { required: true });

watchEffect(() => {
  search();
});
async function search() {
  emit('search');
}
</script>

<template>
  <NCard :bordered="false" size="small" class="user-search-card rd-full px-6">
    <NForm ref="formRef" :model="model" label-placement="left" :show-feedback="false" inline class="user-search-form">
      <NFormItem label="关键词" path="keyword">
        <NInput v-model:value="model.keyword" placeholder="请输入关键词" clearable />
      </NFormItem>
      <NFormItem label="组织标签" path="userGender">
        <OrgTagCascader v-model:value="model.orgTag" clearable class="w-200px!" />
      </NFormItem>
      <NFormItem label="启用状态" path="status">
        <NSelect
          v-model:value="model.status"
          placeholder="请选择启用状态"
          :options="enableStatusOptions"
          clearable
          class="w-200px!"
        />
      </NFormItem>
    </NForm>
  </NCard>
</template>

<style scoped>
.user-search-form {
  flex-wrap: wrap;
}

@media (max-width: 639px) {
  .user-search-card {
    border-radius: 12px;
    padding-inline: 0;
  }

  .user-search-form {
    display: flex;
    flex-direction: column;
  }

  :deep(.n-form-item) {
    margin-right: 0;
  }

  :deep(.n-input),
  :deep(.n-base-selection),
  :deep(.n-cascader) {
    width: 100%;
  }
}
</style>
