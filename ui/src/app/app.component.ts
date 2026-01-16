import { Component, computed, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FileNode, ChatMessage } from './models';
import { FileTreeComponent } from './components/file-tree.component';
import { GithubImportService } from './services/github-import.service';
import { AiChatService, ContextMode, ContextFile } from './services/ai-chat.service';
import { WorkspaceService } from './services/workspace.service';
import { HttpClientModule } from '@angular/common/http';
import { AuthService } from './services/auth.service';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

function uid(prefix = 'id') {
  return `${prefix}_${Math.random().toString(16).slice(2)}_${Date.now()}`;
}

type GithubCtx = { owner: string; repo: string; branch?: string; subPath?: string };

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, FileTreeComponent, HttpClientModule],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent implements OnInit {
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

  constructor(
    private gh: GithubImportService,
    private ai: AiChatService,
    private auth: AuthService,
    private workspace: WorkspaceService,
    private sanitizer: DomSanitizer
  ) {}

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

    if (localStorage.getItem('openGithubModalAfterLogin') === '1') {
      localStorage.removeItem('openGithubModalAfterLogin');
      this.openGithubModal();
    }
  }

  toggleImportMenu() {
    this.importMenuOpen.set(!this.importMenuOpen());
  }

  triggerDeviceImport() {
    this.importMenuOpen.set(false);
  }

  openDeviceFilePicker(fileInput: HTMLInputElement) {
    this.importMenuOpen.set(false);
    fileInput.value = '';
    fileInput.click();
  }

  openDeviceFolderPicker(folderInput: HTMLInputElement) {
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
    const input = ev.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    if (!files.length) return;

    // ✅ append to local map (keep previously imported ones too)
    const filesWithPath: { path: string; file: File }[] = [];
    for (const f of files) {
      this.localFilesByPath.set(f.name, f);
      filesWithPath.push({ path: f.name, file: f });
    }

    const newNodes = buildTreeFromFileList(files.map(f => ({ path: f.name, name: f.name })));
    this.upsertDeviceRootAndMerge(newNodes);

    // ✅ Upload to backend for AI context
    this.uploadDeviceFilesToBackend(filesWithPath);
  }

  onDeviceFolderSelected(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
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
  }

  // ---------- GitHub import (NEW ROOT per repo) ----------
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

    this.gh.importRepo({ owner, repo, branch, subPath }).subscribe({
      next: (tree) => {
        const roots = [...this.fileTree()];

        // Root folder that carries repo meta for preview + refresh
        const ghRoot: FileNode = {
          id: uid('ghroot'),
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

        this.githubModalOpen.set(false);

        // GitHub import does NOT clear localFilesByPath anymore (keep both sources)
        // localFilesByPath is only used when the selected node.path matches a local key.
      },
      error: (err) => {
        if (err?.status === 401) {
          this.githubConnected.set(false);
          this.ghError.set('Not authenticated. Click "Connect GitHub" first.');
        } else {
          this.ghError.set(err?.error?.message || 'GitHub import failed. Check backend endpoint and auth.');
        }
      },
      complete: () => this.ghLoading.set(false)
    });
  }

  // ✅ Refresh a GitHub root folder (re-import same repo)
  refreshGithubFolder(node: FileNode) {
    const meta = node.githubMeta as GithubCtx | undefined;
    if (!meta) return;

    const { owner, repo, branch, subPath } = meta;

    this.gh.importRepo({ owner, repo, branch, subPath }).subscribe({
      next: (tree) => {
        const roots = [...this.fileTree()];
        const idx = roots.findIndex(r => r.id === node.id);
        if (idx >= 0) {
          roots[idx] = {
            ...roots[idx],
            expanded: true,
            children: tree ?? []
          };
          this.fileTree.set(roots);
        }
      },
      error: (err) => {
        const msg = err?.error?.message || 'Refresh failed. Are you authenticated?';
        this.ghError.set(msg);
      }
    });
  }

  // ---------- Delete selected ----------
  deleteSelected() {
    const selected = this.selectedFile();
    if (!selected) return;

    // remove from tree
    const roots = removeNodeById([...this.fileTree()], selected.id);
    this.fileTree.set(roots);

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

    if (!isText) {
      // still ok: we try to read it as text
    }
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
        // if selected path starts with this root's namespace, it's this repo
        // root.path is "from-github/owner/repo"
        // node.path is relative file path inside repo (your tree uses that)
        // So instead of comparing by prefix, we just check if node exists under that root by id.
        if (containsNodeId(r, node.id)) return r.githubMeta as GithubCtx;
      }
    }
    return null;
  }

  // ---------- Chat logic unchanged ----------
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

    // Debug logging
    console.log('[AppComponent] Context mode:', mode);
    console.log('[AppComponent] Selected context files:', contextFiles);

    this.ai.chat({
      message: text,
      context: mode === 'all'
        ? { mode: 'all' }
        : { mode: 'selected', files: contextFiles }
    }).subscribe({
      next: (res) => {
        const reply = res?.reply ?? '(no reply)';
        const botMsg: ChatMessage = { id: uid('m'), role: 'assistant', content: reply, ts: Date.now() };
        this.messages.set([...this.messages(), botMsg]);
      },
      error: (err) => {
        const botMsg: ChatMessage = {
          id: uid('m'),
          role: 'assistant',
          content: err?.error?.message || 'AI call failed. Check backend /api/ai/chat.',
          ts: Date.now()
        };
        this.messages.set([...this.messages(), botMsg]);
      },
      complete: () => this.sending.set(false)
    });
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
}

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
