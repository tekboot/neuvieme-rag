import { Component, computed, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterOutlet, Router, RouterLink, NavigationEnd } from '@angular/router';
import { filter } from 'rxjs/operators';
import { FileNode, ChatMessage } from './models';
import { FileTreeComponent } from './components/file-tree.component';
import { GithubImportService } from './services/github-import.service';
import { AiChatService, ContextMode, ContextFile } from './services/ai-chat.service';
import { WorkspaceService } from './services/workspace.service';
import { ModelsService, ModelInfo } from './services/models.service';
import { HttpClientModule } from '@angular/common/http';
import { AuthService } from './services/auth.service';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ThemeService } from './services/theme.service';
import { ProjectStore } from './services/project-store.service';
import { IndexingService, IndexRequest } from './services/indexing.service';
import { RagChatService } from './services/rag-chat.service';

function uid(prefix = 'id') {
  return `${prefix}_${Math.random().toString(16).slice(2)}_${Date.now()}`;
}

type GithubCtx = { owner: string; repo: string; branch?: string; subPath?: string };

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, FileTreeComponent, HttpClientModule, RouterOutlet, RouterLink],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent implements OnInit {
  // ✅ Routing: check if we're on chat page or admin pages
  isRootRoute = signal(true);

  // ✅ Model selector
  availableModels = signal<ModelInfo[]>([]);
  selectedModel = signal<string>('qwen2.5-coder:7b');
  modelDropdownOpen = signal(false);

  // ✅ workspace tree (multiple roots) - starts empty, "From device" created on first import
  fileTree = signal<FileNode[]>([]);

  selectedFile = signal<FileNode | null>(null);
  selectedFilePath = computed(() => this.selectedFile()?.path ?? null);

  // ✅ Context mode: 'all' or 'selected'
  contextMode = signal<ContextMode>('all');

  // ✅ Computed: list of selected context files with full metadata (only files, not folders)
  selectedContextFiles = computed(() => {
    const files: ContextFile[] = [];
    const roots = this.fileTree();

    const collect = (nodes: FileNode[], parentGithubMeta: GithubCtx | null) => {
      for (const n of nodes) {
        // Inherit github meta from root, or use node's own if it's a github root
        const effectiveMeta = n.githubMeta as GithubCtx | undefined ?? parentGithubMeta;

        if (n.type === 'file' && n.selected) {
          // Determine source: check if path exists in localFilesByPath or if parent has github meta
          const isDeviceFile = this.localFilesByPath.has(n.path);
          const source: 'device' | 'github' = isDeviceFile ? 'device' : (effectiveMeta ? 'github' : 'device');

          const contextFile: ContextFile = {
            source,
            path: n.path,
          };

          if (source === 'github' && effectiveMeta) {
            contextFile.github = {
              owner: effectiveMeta.owner,
              repo: effectiveMeta.repo,
              branch: effectiveMeta.branch,
              subPath: effectiveMeta.subPath,
            };
          }

          files.push(contextFile);
        }

        if (n.children?.length) {
          collect(n.children, effectiveMeta ?? null);
        }
      }
    };

    // Start collection from roots, each root may have its own github meta
    for (const root of roots) {
      const rootMeta = root.githubMeta as GithubCtx | undefined;
      if (root.children?.length) {
        collect(root.children, rootMeta ?? null);
      }
    }

    return files;
  });

  // ✅ Computed: count of selected files (for UI display)
  selectedFilePaths = computed(() => this.selectedContextFiles().map(f => f.path));

  messages = signal<ChatMessage[]>([
    { id: uid('m'), role: 'assistant', content: 'Hi! Import a project (device or GitHub) and tell me what you want to change.', ts: Date.now() }
  ]);

  draft = '';
  sending = signal(false);

  importMenuOpen = signal(false);

  githubModalOpen = signal(false);
  ghOwner = '';
  ghRepo = '';
  ghBranch = 'main';
  ghSubPath = '';
  ghLoading = signal(false);
  ghError = signal<string | null>(null);

  githubConnected = signal(false);
  checkingGithub = signal(false);

  // ✅ Local device files (path -> File)
  localFilesByPath = new Map<string, File>();

  // ✅ Preview modal state
  previewOpen = signal(false);
  previewTitle = signal<string>('');
  previewKind = signal<'text' | 'pdf' | 'image' | 'unknown'>('unknown');
  previewText = signal<string>('');
  previewUrl = signal<SafeResourceUrl | null>(null);
  previewError = signal<string | null>(null);
  private objectUrlToRevoke: string | null = null;

  // ✅ One persistent device root
  private deviceRootId = 'device_root';

  // ✅ RAG Signals
  ragContextStrategy = signal<'use_existing' | 'reindex'>('use_existing');
  ragLog = signal<string[]>([]);
  ragStrategyLoading = signal(false);

  // ✅ Ollama Fix Modal
  ollamaFixModalOpen = signal(false);
  ollamaErrorDetails = signal<any>(null);
  noContentErrorDetails = signal<any>(null);


  ngOnInit(): void {
    // Clear return URL state after OAuth redirect
    this.auth.checkReturnUrl();

    // Check auth state on startup
    this.auth.me().subscribe({
      next: (state) => {
        this.githubConnected.set(state.githubAuthenticated);
      },
      error: () => {
        this.githubConnected.set(false);
      }
    });

    // Load available models for chat dropdown
    this.loadModels();

    if (localStorage.getItem('openGithubModalAfterLogin') === '1') {
      localStorage.removeItem('openGithubModalAfterLogin');
      this.openGithubModal();
    }
  }

  // ✅ Theme toggle
  toggleTheme(): void {
    this.themeService.toggle();
  }

  // Expose theme signal for template binding
  theme = this.themeService.theme;

  // ✅ Model selector
  loadModels(): void {
    this.modelsService.getActiveModels().subscribe({
      next: (models) => {
        this.availableModels.set(models);
        // If current selection is not in list, select first available
        if (models.length > 0 && !models.find(m => m.name === this.selectedModel())) {
          this.selectedModel.set(models[0].name);
        }
      },
      error: () => {
        // Silently fail - models dropdown will just be empty
        console.warn('[AppComponent] Failed to load models');
      }
    });
  }

  toggleModelDropdown(): void {
    this.modelDropdownOpen.set(!this.modelDropdownOpen());
  }

  selectModel(modelName: string): void {
    this.selectedModel.set(modelName);
    this.modelDropdownOpen.set(false);
  }

  getModelDisplayName(name: string): string {
    // Shorten model names for display
    return name.split(':')[0];
  }

  toggleImportMenu() {
    this.importMenuOpen.set(!this.importMenuOpen());
  }

  triggerDeviceImport() {
    this.importMenuOpen.set(false);
  }

  openDeviceFilePicker(fileInput: HTMLInputElement) {
    const DEBUG = true;
    if (DEBUG) console.log('[DeviceImport] Picking files...');
    this.importMenuOpen.set(false);
    fileInput.value = '';
    fileInput.click();
  }

  openDeviceFolderPicker(folderInput: HTMLInputElement) {
    const DEBUG = true;
    if (DEBUG) console.log('[DeviceImport] Picking folder...');
    this.importMenuOpen.set(false);
    folderInput.value = '';
    folderInput.click();
  }

  openGithubModal() {
    this.importMenuOpen.set(false);
    this.githubModalOpen.set(true);
    this.ghError.set(null);

    this.checkingGithub.set(true);
    this.auth.me().subscribe({
      next: (state) => this.githubConnected.set(state.githubAuthenticated),
      error: () => this.githubConnected.set(false),
      complete: () => this.checkingGithub.set(false),
    });
  }

  closeGithubModal() {
    this.githubModalOpen.set(false);
    this.ghError.set(null);
  }

  connectGithub() {
    localStorage.setItem('openGithubModalAfterLogin', '1');
    this.auth.connectGithub();
  }

  // ✅ Toggle file selection for context mode
  toggleFileSelection(node: FileNode) {
    if (node.type !== 'file') return;

    const toggle = (nodes: FileNode[]): FileNode[] =>
      nodes.map(n => {
        if (n.id === node.id) return { ...n, selected: !n.selected };
        if (n.children?.length) return { ...n, children: toggle(n.children) };
        return n;
      });

    this.fileTree.set(toggle(this.fileTree()));
  }

  // ✅ selection triggers preview (local or github based on node metadata)
  onSelectFile(node: FileNode) {
    this.selectedFile.set(node);
    if (node.type !== 'file') return;

    // 1) local device file
    const local = this.localFilesByPath.get(node.path);
    if (local) {
      this.openPreviewForLocalFile(node.path, local);
      return;
    }

    // 2) github file (from the github root meta)
    const meta = this.findGithubMetaForNode(node);
    if (!meta) {
      this.openPreviewUnknown(node.path, 'No file content source available (not a local file and no GitHub metadata).');
      return;
    }

    const githubPath = this.joinSubPath(meta.subPath, node.path);
    this.fetchAndPreviewGithubFile(meta.owner, meta.repo, githubPath, meta.branch);
  }

  // ---------- Device import (APPEND to From device root) ----------
  onDeviceFilesSelected(ev: Event) {
    const DEBUG = true;
    if (DEBUG) console.log('[DeviceImport] Files selected event triggered');
    const input = ev.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (DEBUG) console.log(`[DeviceImport] Selected ${files.length} files`);
    if (!files.length) return;

    // ✅ append to local map (keep previously imported ones too)
    const filesWithPath: { path: string; file: File }[] = [];
    for (const f of files) {
      const p = ((f as any).webkitRelativePath || f.name) as string;
      this.localFilesByPath.set(p, f);
      filesWithPath.push({ path: p, file: f });
    }

    const newNodes = buildTreeFromFileList(files.map(f => ({ path: f.name, name: f.name })));
    this.upsertDeviceRootAndMerge(newNodes);

    // ✅ Upload to backend for AI context
    this.uploadDeviceFilesToBackend(filesWithPath);
  }

  onDeviceFolderSelected(ev: Event) {
    const DEBUG = true;
    if (DEBUG) console.log('[DeviceImport] Folder selected event triggered');
    const input = ev.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (DEBUG) console.log(`[DeviceImport] Selected ${files.length} files in folder`);
    if (!files.length) return;

    // ✅ append to local map (keep previously imported ones too)
    const filesWithPath: { path: string; file: File }[] = [];
    for (const f of files) {
      const p = ((f as any).webkitRelativePath || f.name) as string;
      this.localFilesByPath.set(p, f);
      filesWithPath.push({ path: p, file: f });
    }

    const entries = files.map(f => ({
      path: (f as any).webkitRelativePath || f.name,
      name: f.name
    }));

    const newNodes = buildTreeFromFileList(entries);
    this.upsertDeviceRootAndMerge(newNodes);

    // ✅ Upload to backend for AI context
    this.uploadDeviceFilesToBackend(filesWithPath);
  }

  private uploadDeviceFilesToBackend(filesWithPath: { path: string; file: File }[]) {
    // Filter out binary files by extension
    const textExtensions = ['txt', 'md', 'json', 'xml', 'html', 'css', 'js', 'ts', 'tsx', 'jsx',
      'java', 'py', 'rb', 'go', 'rs', 'c', 'cpp', 'h', 'hpp', 'cs', 'php', 'swift', 'kt',
      'yml', 'yaml', 'toml', 'ini', 'cfg', 'conf', 'sh', 'bash', 'zsh', 'sql', 'graphql',
      'vue', 'svelte', 'scss', 'sass', 'less', 'log', 'env', 'gitignore', 'dockerignore'];

    const textFiles = filesWithPath.filter(item => {
      const ext = (item.path.split('.').pop() || '').toLowerCase();
      return textExtensions.includes(ext) || !item.path.includes('.');
    });

    if (!textFiles.length) return;

    this.workspace.uploadDeviceFiles(textFiles).subscribe({
      next: (res) => console.log(`Uploaded ${res.uploaded} files to backend`),
      error: (err) => console.warn('Device file upload failed (backend may be offline):', err)
    });
  }

  private upsertDeviceRootAndMerge(newNodes: FileNode[]) {
    const roots = [...this.fileTree()];
    let deviceRoot = roots.find(r => r.id === this.deviceRootId);

    if (!deviceRoot) {
      deviceRoot = {
        id: this.deviceRootId,
        name: 'From device',
        type: 'folder',
        path: 'from-device',
        expanded: true,
        children: [],
        source: 'device',
      } as FileNode;
      roots.unshift(deviceRoot);
    }

    deviceRoot.expanded = true;
    deviceRoot.children = mergeNodeArrays(deviceRoot.children || [], newNodes);

    this.fileTree.set(roots);

    // ✅ Track device project in ProjectStore (with file tree)
    const fileCount = this.countFiles(deviceRoot.children || []);
    this.projectStore.addOrUpdateProject({
      id: this.deviceRootId,
      source: 'device',
      displayName: 'From device',
      fileCount,
      lastUpdated: Date.now()
    }, deviceRoot.children || []);
  }

  // ---------- GitHub import (NEW ROOT per repo) ----------
  retryGithubImport() {
    this.ghError.set(null);
    this.importFromGithub();
  }

  importFromGithub() {
    this.ghError.set(null);

    if (!this.githubConnected()) {
      this.ghError.set('Not authenticated. Click "Connect GitHub" first.');
      return;
    }

    if (!this.ghOwner.trim() || !this.ghRepo.trim()) {
      this.ghError.set('Owner and repo are required.');
      return;
    }

    const owner = this.ghOwner.trim();
    const repo = this.ghRepo.trim();
    const branch = (this.ghBranch?.trim() || undefined);
    const subPath = (this.ghSubPath?.trim() || undefined);

    this.ghLoading.set(true);
    this.ghError.set(null);

    console.log('[GithubImport] Sending request:', { owner, repo, branch, subPath });

    this.gh.importRepo({ owner, repo, branch, subPath }).subscribe({
      next: (res) => {
        console.log('[GithubImport] Response received:', res);

        // Validate response structure
        if (!res || typeof res !== 'object') {
          console.error('[GithubImport] Invalid response: not an object', res);
          this.ghError.set('Invalid response from server: expected object with project and tree');
          this.ghLoading.set(false);
          return;
        }

        // Check for error response (backend may return 200 with error body)
        if ('code' in res && 'message' in res) {
          console.error('[GithubImport] Server returned error:', res);
          this.ghError.set((res as any).message || 'Import failed');
          this.ghLoading.set(false);
          return;
        }

        const { project, tree } = res;

        if (!project || !project.id) {
          console.error('[GithubImport] Invalid response: missing project.id', res);
          this.ghError.set('Invalid response: missing project data');
          this.ghLoading.set(false);
          return;
        }

        if (!Array.isArray(tree)) {
          console.error('[GithubImport] Invalid response: tree is not an array', res);
          this.ghError.set('Invalid response: expected tree array');
          this.ghLoading.set(false);
          return;
        }

        console.log('[GithubImport] SUCCESS projectId=' + project.id + ' files=' + tree.length);

        const roots = [...this.fileTree()];

        // Root folder that carries repo meta for preview + refresh
        const ghRoot: FileNode = {
          id: project.id, // Use persistence ID from backend
          name: `From GitHub (${repo})`,
          type: 'folder',
          path: `from-github/${owner}/${repo}`,
          expanded: true,
          children: tree ?? [],
          source: 'github',
          githubMeta: { owner, repo, branch, subPath }
        } as any;

        roots.push(ghRoot);
        this.fileTree.set(roots);

        // ✅ Track GitHub project in ProjectStore (with file tree)
        const fileCount = this.countFiles(tree ?? []);
        this.projectStore.addOrUpdateProject({
          id: project.id,
          source: 'github',
          displayName: `${owner}/${repo}`,
          owner,
          repo,
          branch,
          subPath,
          fileCount,
          lastUpdated: Date.now()
        }, tree ?? []);

        this.githubModalOpen.set(false);
        this.ghLoading.set(false);
      },
      error: (err) => {
        console.error('[GithubImport] HTTP Error:', err);

        // Build detailed error message
        let errorMsg = 'GitHub import failed';

        if (err?.status === 401) {
          this.githubConnected.set(false);
          errorMsg = err?.error?.message || 'Not authenticated. Click "Connect GitHub" first.';
        } else if (err?.status === 403) {
          errorMsg = err?.error?.message || 'Access forbidden. Check repository permissions.';
        } else if (err?.status === 404) {
          errorMsg = err?.error?.message || 'Repository or branch not found.';
        } else if (err?.status === 0) {
          errorMsg = 'Network error: Cannot reach backend server at localhost:8081';
        } else if (err?.error?.message) {
          errorMsg = err.error.message;
        } else if (err?.message) {
          errorMsg = `Error: ${err.message}`;
        } else if (err?.status) {
          errorMsg = `HTTP ${err.status}: ${err.statusText || 'Unknown error'}`;
        }

        // Add code if available
        if (err?.error?.code) {
          errorMsg = `[${err.error.code}] ${errorMsg}`;
        }

        console.error('[GithubImport] Final error message:', errorMsg);
        this.ghError.set(errorMsg);
        this.ghLoading.set(false);
      }
    });
  }

  // ✅ Refresh a GitHub root folder (re-import same repo)
  refreshGithubFolder(node: FileNode) {
    const meta = node.githubMeta as GithubCtx | undefined;
    if (!meta) return;

    const { owner, repo, branch, subPath } = meta;
    console.log('[GithubRefresh] Refreshing:', { owner, repo, branch, subPath });

    this.gh.importRepo({ owner, repo, branch, subPath }).subscribe({
      next: (res) => {
        console.log('[GithubRefresh] Response:', res);

        // Check for error response
        if ('code' in res && 'message' in res) {
          console.error('[GithubRefresh] Server returned error:', res);
          this.ghError.set((res as any).message || 'Refresh failed');
          return;
        }

        const { project, tree } = res;

        if (!project || !Array.isArray(tree)) {
          console.error('[GithubRefresh] Invalid response structure');
          this.ghError.set('Invalid response from server');
          return;
        }

        const roots = [...this.fileTree()];
        const idx = roots.findIndex(r => r.id === node.id);
        if (idx >= 0) {
          roots[idx] = {
            ...roots[idx],
            expanded: true,
            children: tree ?? []
          };
          this.fileTree.set(roots);

          // ✅ Update project in ProjectStore (with file tree)
          const fileCount = this.countFiles(tree ?? []);
          this.projectStore.addOrUpdateProject({
            id: project.id,
            source: 'github',
            displayName: `${owner}/${repo}`,
            owner,
            repo,
            branch,
            subPath,
            fileCount,
            lastUpdated: Date.now()
          }, tree ?? []);

          console.log('[GithubRefresh] SUCCESS files=' + fileCount);
        }
      },
      error: (err) => {
        console.error('[GithubRefresh] Error:', err);
        let msg = 'Refresh failed';
        if (err?.error?.message) {
          msg = err.error.message;
        } else if (err?.status === 401) {
          msg = 'Authentication expired. Please reconnect GitHub.';
        } else if (err?.message) {
          msg = err.message;
        }
        this.ghError.set(msg);
      }
    });
  }

  // ---------- Indexing & RAG ----------
  // View state: 'chat' | 'rag'
  activeView = signal<'chat' | 'rag'>('chat');
  indexingProjectId = signal<string | null>(null);
  indexingStatus = signal<any>(null); // IndexStatusResponse

  // Options
  idxChunkSize = signal(1000);
  idxOverlap = signal(200);
  idxEmbedModel = signal('nomic-embed-text'); // default

  // Chat Options
  chatTopK = signal(5);
  availableEmbedModels = ['nomic-embed-text', 'mxbai-embed-large', 'all-minilm'];

  constructor(
    private gh: GithubImportService,
    private ai: AiChatService,
    private auth: AuthService,
    private workspace: WorkspaceService,
    private sanitizer: DomSanitizer,
    private modelsService: ModelsService,
    private router: Router,
    private themeService: ThemeService,
    private projectStore: ProjectStore,
    private indexingService: IndexingService,
    private ragChat: RagChatService
  ) {
    // Track route changes to show/hide chat UI
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd)
    ).subscribe(e => {
      this.isRootRoute.set(e.urlAfterRedirects === '/' || e.urlAfterRedirects === '');
    });
  }

  toggleRagView() {
    this.activeView.set(this.activeView() === 'chat' ? 'rag' : 'chat');
  }

  startIndexing() {
    // 1. Identify Target Project
    // If files are selected, we index those.
    // If no files selected, we try to index the whole "GitHub" project (fallback logic).

    // Check if we have selected files (using the same computed list as Chat Context)
    const selectedFiles = this.selectedContextFiles();
    console.log('[StartIndexing] Selected files count:', selectedFiles.length);
    let targetProjectId = this.indexingProjectId();

    // If no project explicitly set, find from selection or default to first github
    if (!targetProjectId) {
      if (selectedFiles.length > 0) {
        // Try to find the root project for the first selected file
        const first = this.fileTree().find(root => selectedFiles[0].path.startsWith(root.path) || root.source === 'github');
        // Logic above is weak. Let's use `findRootForNode` if we can map path back to node.
        // Or simpler: just grab the first GitHub root if available, or Device root.
        const ghRoot = this.fileTree().find(n => n.source === 'github');
        if (ghRoot) targetProjectId = ghRoot.id;
        else targetProjectId = this.deviceRootId;
      } else {
        // Fallback
        const ghRoot = this.fileTree().find(n => n.source === 'github');
        if (ghRoot) targetProjectId = ghRoot.id;
      }
    }

    if (!targetProjectId) {
      alert('No project found to index. Please import a project.');
      return;
    }
    this.indexingProjectId.set(targetProjectId);

    // 2. Build Payload
    // If user has specific files selected, we use mode='selected' and send valid ContextFile objects
    // If NO files selected, we might want to warn user, OR index everything (which is unsupported by backend for GitHub currently without file list).

    if (selectedFiles.length === 0) {
      alert('Please select at least one file to index (checkbox in Explorer). Full project indexing is not yet supported for GitHub.');
      return;
    }

    // DTO says: String source is a field. We can default to 'github' or 'device'.
    // Let's rely on file-level source    const request: IndexRequest = {
    const request: IndexRequest = {
      projectId: targetProjectId,
      mode: 'selected',
      files: selectedFiles.map(f => ({
        source: f.source,
        path: f.path,
        github: f.github
      })),
      chunkSize: this.idxChunkSize(),
      chunkOverlap: this.idxOverlap(),
      embedModel: this.idxEmbedModel()
    };

    console.log('[StartIndexing] Payload:', JSON.stringify(request, null, 2));
    if (request.files && request.files.length > 0) {
      console.log('[StartIndexing] First file:', request.files[0]);
    }

    this.indexingService.indexProject(request).subscribe({
      next: (_res: { message: string; projectId: string; fileCount?: number }) => {
        this.pollIndexing(targetProjectId!);
      },
      error: (err) => {
        console.error('[Indexing] Error:', err);
        const apiError = err.error;

        if (apiError?.code === 'OLLAMA_UNAVAILABLE' || err.status === 503) {
          this.ollamaErrorDetails.set(apiError);
          this.ollamaFixModalOpen.set(true);
        } else if (apiError?.code === 'NO_INDEXABLE_CONTENT') {
          this.noContentErrorDetails.set(apiError);
          // We could show a specific modal or just use alert with details
          const details = apiError.details ? Object.entries(apiError.details).map(([path, reason]) => `${path}: ${reason}`).join('\n') : '';
          alert(`${apiError.message}\n\n${details}`);
        } else {
          alert('Indexing failed: ' + (apiError?.message || apiError?.error || err.message));
        }
      }
    });
  }

  pollIndexing(projectId: string) {
    this.indexingService.pollStatus(projectId).subscribe({
      next: (status) => this.indexingStatus.set(status),
      error: (err) => console.error(err)
    });
  }

  // Helper to find root
  findRootForNode(node: FileNode): FileNode | null {
    const roots = this.fileTree();
    for (const r of roots) {
      if (r.id === node.id || containsNodeId(r, node.id)) return r;
    }
    return null;
  }

  // ---------- Delete selected ----------
  deleteSelected() {
    const selected = this.selectedFile();
    if (!selected) return;

    // remove from tree
    const roots = removeNodeById([...this.fileTree()], selected.id);
    this.fileTree.set(roots);

    // ✅ If deleting a GitHub root, remove from ProjectStore
    if (selected.type === 'folder' && selected.source === 'github' && selected.githubMeta) {
      this.projectStore.removeProject(selected.id);
    }

    // remove local file mapping if it was a local file
    if (selected.type === 'file') {
      this.localFilesByPath.delete(selected.path);
    }

    this.selectedFile.set(null);
  }

  // ---------- Preview helpers (local + github) ----------
  closePreview() {
    this.previewOpen.set(false);
    this.previewError.set(null);
    this.previewText.set('');
    this.previewKind.set('unknown');
    this.previewTitle.set('');
    this.previewUrl.set(null);

    if (this.objectUrlToRevoke) {
      URL.revokeObjectURL(this.objectUrlToRevoke);
      this.objectUrlToRevoke = null;
    }
  }

  private openPreviewUnknown(path: string, message: string) {
    this.previewTitle.set(path);
    this.previewKind.set('unknown');
    this.previewText.set('');
    this.previewUrl.set(null);
    this.previewError.set(message);
    this.previewOpen.set(true);
  }

  private openPreviewForLocalFile(path: string, file: File) {
    this.previewTitle.set(path);
    this.previewError.set(null);
    this.previewOpen.set(true);

    if (this.objectUrlToRevoke) {
      URL.revokeObjectURL(this.objectUrlToRevoke);
      this.objectUrlToRevoke = null;
    }

    const ext = (path.split('.').pop() || '').toLowerCase();
    const isPdf = ext === 'pdf';
    const isImage = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(ext);
    const isText = ['txt', 'md', 'json', 'xml', 'html', 'css', 'js', 'ts', 'java', 'yml', 'yaml', 'log'].includes(ext);

    if (isPdf) {
      const rawUrl = URL.createObjectURL(file);
      this.objectUrlToRevoke = rawUrl;
      this.previewUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(rawUrl));
      this.previewKind.set('pdf');
      this.previewText.set('');
      return;
    }

    if (isImage) {
      const rawUrl = URL.createObjectURL(file);
      this.objectUrlToRevoke = rawUrl;
      this.previewUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(rawUrl));
      this.previewKind.set('image');
      this.previewText.set('');
      return;
    }

    const reader = new FileReader();
    reader.onload = () => {
      this.previewKind.set('text');
      this.previewUrl.set(null);
      this.previewText.set(String(reader.result || ''));
    };
    reader.onerror = () => this.openPreviewUnknown(path, 'Failed to read local file.');
    reader.readAsText(file);
  }

  private joinSubPath(subPath: string | undefined, nodePath: string): string {
    const cleanNode = (nodePath || '').replace(/^\/+/, '');
    const cleanSub = (subPath || '').trim().replace(/^\/+/, '').replace(/\/+$/, '');
    if (!cleanSub) return cleanNode;
    return `${cleanSub}/${cleanNode}`;
  }

  private fetchAndPreviewGithubFile(owner: string, repo: string, path: string, ref?: string) {
    this.previewTitle.set(path);
    this.previewKind.set('text');
    this.previewText.set('Loading file from GitHub…');
    this.previewUrl.set(null);
    this.previewError.set(null);
    this.previewOpen.set(true);

    (this.gh as any).getFile({ owner, repo, path, ref }).subscribe({
      next: (res: any) => {
        const contentB64 = res?.content;
        const encoding = res?.encoding;

        if (!contentB64 || encoding !== 'base64') {
          this.openPreviewUnknown(path, 'This file has no base64 content in the response.');
          return;
        }

        const bytes = this.base64ToUint8Array(contentB64);
        this.openPreviewFromBytes(path, bytes);
      },
      error: (err: any) => {
        if (err?.status === 401) {
          this.githubConnected.set(false);
          this.openPreviewUnknown(path, 'Not authenticated. Please click “Connect GitHub” then try again.');
        } else {
          this.openPreviewUnknown(path, err?.error?.message || 'Failed to fetch file from GitHub.');
        }
      }
    });
  }

  private base64ToUint8Array(b64: string): Uint8Array {
    const clean = b64.replace(/\s/g, '');
    const binary = atob(clean);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  private bytesToArrayBuffer(bytes: Uint8Array): ArrayBuffer {
    return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  }

  private openPreviewFromBytes(path: string, bytes: Uint8Array) {
    if (this.objectUrlToRevoke) {
      URL.revokeObjectURL(this.objectUrlToRevoke);
      this.objectUrlToRevoke = null;
    }

    const ext = (path.split('.').pop() || '').toLowerCase();

    const isPdf = ext === 'pdf';
    const isImage = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(ext);
    const isText = ['txt', 'md', 'json', 'xml', 'html', 'css', 'js', 'ts', 'java', 'yml', 'yaml', 'log'].includes(ext);

    if (isPdf) {
      const blob = new Blob([this.bytesToArrayBuffer(bytes)], { type: 'application/pdf' });
      const rawUrl = URL.createObjectURL(blob);
      this.objectUrlToRevoke = rawUrl;
      this.previewUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(rawUrl));
      this.previewKind.set('pdf');
      this.previewText.set('');
      return;
    }

    if (isImage) {
      const mime = ext === 'svg' ? 'image/svg+xml' : `image/${ext === 'jpg' ? 'jpeg' : ext}`;
      const blob = new Blob([this.bytesToArrayBuffer(bytes)], { type: mime });
      const rawUrl = URL.createObjectURL(blob);
      this.objectUrlToRevoke = rawUrl;
      this.previewUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(rawUrl));
      this.previewKind.set('image');
      this.previewText.set('');
      return;
    }

    if (isText) {
      const text = new TextDecoder('utf-8').decode(bytes);
      this.previewKind.set('text');
      this.previewUrl.set(null);
      this.previewText.set(text);
      return;
    }

    try {
      const text = new TextDecoder('utf-8').decode(bytes);
      this.previewKind.set('text');
      this.previewUrl.set(null);
      this.previewText.set(text || 'Preview not supported for this type.');
    } catch {
      this.openPreviewUnknown(path, 'Preview not supported for this file type.');
    }
  }

  // ✅ Find which GitHub root folder contains this node (so preview works for multiple repos)
  private findGithubMetaForNode(node: FileNode): GithubCtx | null {
    const roots = this.fileTree();
    for (const r of roots) {
      if (r.type === 'folder' && r.source === 'github' && r.githubMeta) {
        if (containsNodeId(r, node.id)) return r.githubMeta as GithubCtx;
      }
    }
    return null;
  }

  handleKeydown(ev: KeyboardEvent) {
    if (ev.key === 'Enter' && !ev.shiftKey) {
      ev.preventDefault();
      this.send(ev);
    }
  }

  send(ev: Event) {
    ev.preventDefault();
    const text = this.draft.trim();
    if (!text || this.sending()) return;

    const userMsg: ChatMessage = { id: uid('m'), role: 'user', content: text, ts: Date.now() };
    this.messages.set([...this.messages(), userMsg]);
    this.draft = '';

    this.sending.set(true);

    const mode = this.contextMode();
    const contextFiles = this.selectedContextFiles();
    const model = this.selectedModel();

    // RAG Logic
    const allProjects = this.projectStore.getProjects().map(p => p.id);
    if (allProjects.length === 0) {
      this.handleError({ error: { message: 'No projects found to query.' } });
      this.sending.set(false);
      return;
    }

    this.ragLog.set(['Initializing RAG query...']);

    this.ragChat.chat({
      message: text,
      projectIds: allProjects,
      mode: mode,
      strategy: this.ragContextStrategy(),
      files: contextFiles.map(f => ({
        source: f.source,
        path: f.path,
        github: f.github ? {
          owner: f.github.owner,
          repo: f.github.repo,
          branch: f.github.branch,
          subPath: f.github.subPath
        } : undefined
      })),
      embedModel: this.idxEmbedModel(),
      chunkSize: this.idxChunkSize(),
      chunkOverlap: this.idxOverlap(),
      topK: this.chatTopK(),
      model: model
    }).subscribe({
      next: (res) => this.handleReply(res),
      error: (err) => this.handleError(err),
      complete: () => this.sending.set(false)
    });
  }

  handleReply(res: any) {
    const reply = res?.answer || res?.reply || '(no reply)';
    const botMsg: ChatMessage = { id: uid('m'), role: 'assistant', content: reply, ts: Date.now() };
    this.messages.set([...this.messages(), botMsg]);

    if (res?.rag?.messageLog) {
      this.ragLog.set(res.rag.messageLog);
    }
  }

  handleError(err: any) {
    const botMsg: ChatMessage = {
      id: uid('m'),
      role: 'assistant',
      content: err?.error?.message || 'AI call failed.',
      ts: Date.now()
    };
    this.messages.set([...this.messages(), botMsg]);
  }

  clearChat() {
    this.messages.set([
      { id: uid('m'), role: 'assistant', content: 'Chat cleared. What do you want to do next?', ts: Date.now() }
    ]);
  }

  formatTime(ts: number) {
    const d = new Date(ts);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  // Helper: count files recursively in a tree
  private countFiles(nodes: FileNode[]): number {
    let count = 0;
    for (const node of nodes) {
      if (node.type === 'file') {
        count++;
      } else if (node.children) {
        count += this.countFiles(node.children);
      }
    }
    return count;
  }

  // ✅ Error handling helper
  private handleApiError(err: any, fallbackTitle: string) {
    let msg = fallbackTitle;
    const apiError = err.error;

    if (err.status === 0) {
      msg = 'Network error: Cannot reach backend server at localhost:8081';
    } else if (apiError?.message) {
      msg = apiError.message;
    } else if (err.message) {
      msg = err.message;
    }

    if (apiError?.code) {
      msg = `[${apiError.code}] ${msg}`;
    }

    if (err.status === 401) {
      this.githubConnected.set(false);
    }

    if (fallbackTitle === 'GitHub import failed') {
      this.ghError.set(msg);
      this.ghLoading.set(false);
    } else {
      alert(msg);
    }
  }

  closeOllamaFixModal() {
    this.ollamaFixModalOpen.set(false);
  }

  retryIndexing() {
    this.ollamaFixModalOpen.set(false);
    this.startIndexing();
  }
}

// Helper to check if node exists in tree


// ---------- helpers ----------
function buildTreeFromFileList(entries: { path: string; name: string }[]): FileNode[] {
  const clean = entries
    .map(e => ({ ...e, path: e.path.replace(/\\/g, '/').replace(/^\/+/, '') }))
    .filter(e => !!e.path);

  const root: FileNode[] = [];

  const ensureFolder = (parent: FileNode[], folderName: string, folderPath: string) => {
    let found = parent.find(n => n.type === 'folder' && n.name === folderName && n.path === folderPath);
    if (!found) {
      found = { id: uid('n'), name: folderName, type: 'folder', path: folderPath, expanded: false, children: [] };
      parent.push(found);
      parent.sort(nodeSort);
    }
    return found;
  };

  for (const e of clean) {
    const parts = e.path.split('/').filter(Boolean);
    let cursor = root;
    let currentPath = '';

    for (let i = 0; i < parts.length; i++) {
      const part = parts[i];
      currentPath = currentPath ? `${currentPath}/${part}` : part;

      const isLast = i === parts.length - 1;
      if (isLast) {
        if (!cursor.find(n => n.type === 'file' && n.path === currentPath)) {
          cursor.push({ id: uid('n'), name: part, type: 'file', path: currentPath });
          cursor.sort(nodeSort);
        }
      } else {
        const folder = ensureFolder(cursor, part, currentPath);
        cursor = folder.children!;
      }
    }
  }

  return root;
}

function nodeSort(a: FileNode, b: FileNode) {
  if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
  return a.name.localeCompare(b.name);
}

function mergeNodeArrays(existing: FileNode[], newNodes: FileNode[]): FileNode[] {
  const out = [...existing];

  for (const n of newNodes) {
    const same = out.find(x => x.type === n.type && x.path === n.path && x.name === n.name);
    if (!same) {
      out.push(n);
    } else if (same.type === 'folder') {
      same.children = mergeNodeArrays(same.children || [], n.children || []);
    }
  }

  out.sort(nodeSort);
  return out;
}

function removeNodeById(nodes: FileNode[], id: string): FileNode[] {
  const filtered = nodes
    .filter(n => n.id !== id)
    .map(n => {
      if (n.children?.length) {
        return { ...n, children: removeNodeById(n.children, id) };
      }
      return n;
    });

  return filtered;
}

function containsNodeId(root: FileNode, id: string): boolean {
  if (root.id === id) return true;
  if (!root.children?.length) return false;
  for (const c of root.children) {
    if (containsNodeId(c, id)) return true;
  }
  return false;
}
