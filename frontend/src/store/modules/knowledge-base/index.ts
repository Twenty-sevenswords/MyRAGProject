import { REQUEST_ID_KEY } from '~/packages/axios/src';
import { nanoid } from '~/packages/utils/src';

export const useKnowledgeBaseStore = defineStore(SetupStoreId.KnowledgeBase, () => {
  const tasks = ref<Api.KnowledgeBase.UploadTask[]>([]);
  const activeUploads = ref<Set<string>>(new Set());

  async function uploadChunk(task: Api.KnowledgeBase.UploadTask): Promise<boolean> {
    const totalChunks = Math.ceil(task.totalSize / chunkSize);
    const chunkStart = task.chunkIndex * chunkSize;
    const chunkEnd = Math.min(chunkStart + chunkSize, task.totalSize);
    task.chunk = task.file.slice(chunkStart, chunkEnd);

    const requestId = nanoid();
    task.requestIds ??= [];
    task.requestIds.push(requestId);

    const { error, data } = await request<Api.KnowledgeBase.Progress>({
      url: '/upload/chunk',
      method: 'POST',
      data: {
        file: task.chunk,
        fileMd5: task.fileMd5,
        chunkIndex: task.chunkIndex,
        totalSize: task.totalSize,
        fileName: task.fileName,
        orgTag: task.orgTag,
        isPublic: task.isPublic ?? false
      },
      headers: {
        'Content-Type': 'multipart/form-data',
        [REQUEST_ID_KEY]: requestId
      },
      timeout: 10 * 60 * 1000
    });

    task.requestIds = task.requestIds.filter(id => id !== requestId);

    if (error || !data) return false;

    const updatedTask = tasks.value.find(t => t.fileMd5 === task.fileMd5);
    if (!updatedTask) return false;

    updatedTask.uploadedChunks = data.uploaded;
    updatedTask.progress = Number.parseFloat(data.progress.toFixed(2));

    if (data.uploaded.length === totalChunks) {
      const success = await mergeFile(task);
      if (!success) return false;
    }

    return true;
  }

  async function mergeFile(task: Api.KnowledgeBase.UploadTask): Promise<boolean> {
    try {
      const { error } = await request({
        url: '/upload/merge',
        method: 'POST',
        data: { fileMd5: task.fileMd5, fileName: task.fileName }
      });
      if (error) return false;

      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      if (index >= 0) {
        tasks.value[index].status = UploadStatus.Completed;
        tasks.value[index].progress = 100;
      }
      return true;
    } catch {
      return false;
    }
  }

  async function enqueueUpload(form: Api.KnowledgeBase.Form) {
    const file = form.fileList?.[0]?.file;
    if (!file) {
      window.$message?.error('Please select a file first.');
      return;
    }

    const md5 = await calculateMD5(file);
    const existingTask = tasks.value.find(t => t.fileMd5 === md5);

    if (existingTask) {
      if (existingTask.status === UploadStatus.Completed) {
        window.$message?.error('This file is already uploaded.');
        return;
      }
      if (existingTask.status === UploadStatus.Pending || existingTask.status === UploadStatus.Uploading) {
        window.$message?.error('This file is currently uploading.');
        return;
      }
      if (existingTask.status === UploadStatus.Break) {
        existingTask.status = UploadStatus.Pending;
        void startUpload();
        return;
      }
    }

    const newTask: Api.KnowledgeBase.UploadTask = {
      file,
      chunk: null,
      chunkIndex: 0,
      fileMd5: md5,
      fileName: file.name,
      totalSize: file.size,
      isPublic: form.isPublic,
      uploadedChunks: [],
      progress: 0,
      status: UploadStatus.Pending,
      orgTag: form.orgTag,
      orgTagName: form.orgTagName ?? null
    };

    tasks.value.push(newTask);
    void startUpload();
  }

  async function startUpload() {
    if (activeUploads.value.size >= 3) return;

    const task = tasks.value.find(
      t => t.status === UploadStatus.Pending && !activeUploads.value.has(t.fileMd5)
    );
    if (!task) return;

    task.status = UploadStatus.Uploading;
    activeUploads.value.add(task.fileMd5);

    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    try {
      if (task.uploadedChunks.length === totalChunks) {
        const merged = await mergeFile(task);
        if (!merged) throw new Error('File merge failed.');
      }

      for (let i = 0; i < totalChunks; i += 1) {
        if (!task.uploadedChunks.includes(i)) {
          task.chunkIndex = i;
          // eslint-disable-next-line no-await-in-loop
          const success = await uploadChunk(task);
          if (!success) throw new Error('Chunk upload failed.');
        }
      }
    } catch (error) {
      console.error('Upload failed:', error);
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      if (index >= 0) {
        tasks.value[index].status = UploadStatus.Break;
      }
    } finally {
      activeUploads.value.delete(task.fileMd5);
      void startUpload();
    }
  }

  return {
    tasks,
    activeUploads,
    enqueueUpload,
    startUpload
  };
});
