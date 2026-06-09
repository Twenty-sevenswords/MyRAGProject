<script setup lang="ts">
import { useFullscreen } from '@vueuse/core';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalSearch from '../global-search/index.vue';
import ThemeButton from './components/theme-button.vue';
import UserAvatar from './components/user-avatar.vue';

defineOptions({
  name: 'GlobalHeader'
});

interface Props {
  /** Whether to show the logo */
  // showLogo?: App.Global.HeaderProps['showLogo'];
  /** Whether to show the menu toggler */
  showMenuToggler?: App.Global.HeaderProps['showMenuToggler'];
  /** Whether to show the menu */
  // showMenu?: App.Global.HeaderProps['showMenu'];
}

defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();
const { isFullscreen, toggle } = useFullscreen();

const isDev = import.meta.env.DEV;
</script>

<template>
  <DarkModeContainer class="global-header h-full flex-y-center justify-between bg-transparent">
    <div id="header-extra" class="header-extra h-full flex-col justify-center rd-full bg-container shadow-2xl"></div>
    <!-- <GlobalLogo v-if="showLogo" class="h-full" :style="{ width: themeStore.sider.width + 'px' }" /> -->
    <MenuToggler
      v-if="showMenuToggler && appStore.isMobile"
      :collapsed="appStore.siderCollapse"
      @click="appStore.toggleSiderCollapse"
    />
    <!--
    <div v-if="showMenu" :id="GLOBAL_HEADER_MENU_ID" class="h-full flex-y-center flex-1-hidden"></div>
    <div v-else class="h-full flex-y-center flex-1-hidden">
      <GlobalBreadcrumb v-if="!appStore.isMobile" class="ml-12px" />
    </div>
-->
    <div class="header-actions h-full flex-y-center justify-end rd-full bg-container px-8 shadow-2xl">
      <GlobalSearch />
      <FullScreen v-if="!appStore.isMobile" :full="isFullscreen" @click="toggle" />
      <LangSwitch
        v-if="themeStore.header.multilingual.visible"
        :lang="appStore.locale"
        :lang-options="appStore.localeOptions"
        @change-lang="appStore.changeLocale"
      />
      <ThemeSchemaSwitch
        :theme-schema="themeStore.themeScheme"
        :is-dark="themeStore.darkMode"
        @switch="themeStore.toggleThemeScheme"
      />
      <ThemeButton v-if="isDev && !appStore.isMobile" />
      <UserAvatar />
    </div>
  </DarkModeContainer>
</template>

<style scoped>
.global-header {
  min-width: 0;
  gap: 12px;
  margin-left: 48px;
}

.header-extra {
  min-width: 0;
  max-width: calc(100% - 260px);
  overflow: hidden;
}

.header-actions {
  flex-shrink: 0;
  gap: 2px;
}

@media (max-width: 639px) {
  .global-header {
    gap: 8px;
    margin-left: 0;
    padding-inline: 6px;
  }

  .header-extra {
    display: none;
  }

  .header-actions {
    min-width: 0;
    padding-inline: 6px;
  }
}
</style>
