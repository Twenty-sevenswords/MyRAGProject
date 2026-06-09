<script setup lang="ts">
const { userInfo } = storeToRefs(useAuthStore());

const tags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});

const loading = ref(false);
const getOrgTags = async () => {
  loading.value = true;
  const { error, data } = await request<Api.OrgTag.Mine>({
    url: '/users/org-tags'
  });
  if (!error) {
    tags.value = data;
  }
  loading.value = false;
};

onMounted(() => {
  getOrgTags();
});

const visible = ref(false);
const currentTagId = ref('');
const showModal = (tagId: string) => {
  if (tagId === tags.value.primaryOrg) return;
  visible.value = true;
  currentTagId.value = tagId;
};
const submitLoading = ref(false);
const setPrimaryOrg = async () => {
  submitLoading.value = true;
  const { error } = await request({
    url: '/users/primary-org',
    method: 'PUT',
    data: { primaryOrg: currentTagId.value, userId: userInfo.value.id }
  });
  if (!error) {
    visible.value = false;
    getOrgTags();
  }
  submitLoading.value = false;
};
</script>

<template>
  <NSpin :show="loading">
    <div class="personal-center-page flex-cc">
      <NCard class="personal-card min-h-400px w-50vw card-wrapper" :segmented="{ content: true, footer: 'soft' }">
        <template #header>
          <div class="flex items-center gap-4">
            <NAvatar size="large">
              <icon-solar:user-circle-linear class="text-icon-large" />
            </NAvatar>
            <div>{{ userInfo.username }}</div>
          </div>
        </template>
        <NScrollbar class="max-h-60vh">
          <div class="flex flex-wrap gap-4 p-4">
            <NCard
              v-for="tag in tags.orgTagDetails"
              :key="tag.tagId"
              size="small"
              embedded
              hoverable
              class="org-tag-card w-[calc((100%-32px)/3)]"
              :segmented="{ content: true, footer: 'soft' }"
              @click="showModal(tag.tagId)"
            >
              <div class="flex items-center justify-between">
                <div>{{ tag.name }}</div>
                <NTag v-if="tag.tagId === tags.primaryOrg" type="primary" size="small">
                  主标签
                  <template #icon>
                    <icon-solar:verified-check-bold-duotone class="text-icon" />
                  </template>
                </NTag>
              </div>
              <template #footer>
                <NEllipsis :line-clamp="3">{{ tag.description }}</NEllipsis>
              </template>
            </NCard>
          </div>
        </NScrollbar>
      </NCard>

      <NModal
        v-model:show="visible"
        :loading="submitLoading"
        preset="dialog"
        title="设置主标签"
        content="确定将当前标签设置为主标签吗？"
        positive-text="确认"
        negative-text="取消"
        @positive-click="setPrimaryOrg"
        @negative-click="visible = false"
      />
    </div>
  </NSpin>
</template>

<style scoped lang="scss">
.personal-card {
  min-width: 600px;
}

:deep(.n-card__content) {
  flex: none m !important;
  height: fit-content;
}

@media (max-width: 639px) {
  .personal-center-page {
    align-items: stretch;
    justify-content: stretch;
  }

  .personal-card {
    min-width: 0;
    width: 100%;
  }

  .org-tag-card {
    width: 100% !important;
  }
}

@media (min-width: 640px) and (max-width: 900px) {
  .personal-card {
    min-width: 0;
    width: 100%;
  }

  .org-tag-card {
    width: calc((100% - 16px) / 2) !important;
  }
}
</style>
