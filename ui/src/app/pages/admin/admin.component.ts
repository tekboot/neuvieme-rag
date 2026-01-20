import { Component, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { OllamaService, OllamaModel } from '../../services/ollama.service';
import { ProjectStore, ProjectSummary } from '../../services/project-store.service';
import { ThemeService } from '../../services/theme.service';
import { IndexingService, IndexStatusResponse, ContextFile } from '../../services/indexing.service';
import { FileNode } from '../../models';

interface ProjectFile {
  path: string;
  source: 'device' | 'github';
  size?: string;
  status: 'pending' | 'indexed' | 'error';
}

interface Project {
  id: string;
  name: string;
  source: 'device' | 'github';
  files: ProjectFile[];
  fileCount?: number;
  indexingStatus: 'not_started' | 'in_progress' | 'completed' | 'error';
}

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './admin.component.html',
  styleUrls: ['./admin.component.scss']
})
export class AdminComponent implements OnInit {
  // Models - now using real Ollama data
  models = signal<OllamaModel[]>([]);
  modelsLoading = signal(false);
  modelsError = signal<string | null>(null);

  // Active model selection (stored in localStorage)
  activeModel = signal<string>(localStorage.getItem('activeModel') || 'qwen2.5-coder');

  // Embed models static list
  embedModels = signal(['nomic-embed-text', 'mxbai-embed-large', 'all-minilm']);

  // Installed models tracking (persisted to localStorage)
  installedModels = signal<string[]>(JSON.parse(localStorage.getItem('ollamaInstalledModels') || '[]'));
  installingModels = signal<Set<string>>(new Set());

  // Projects - now using real data from ProjectStore
  projects = signal<Project[]>([]);
  selectedProject = signal<Project | null>(null);
  indexingOptions = signal({
    chunkSize: 500,
    overlap: 50,
    embedModel: 'nomic-embed-text'
  });

  // Indexing status tracking
  indexingStatus = signal<IndexStatusResponse | null>(null);
  indexingError = signal<string | null>(null);

  constructor(
    private ollamaService: OllamaService,
    private projectStore: ProjectStore,
    public themeService: ThemeService,
    private indexingService: IndexingService
  ) { }

  get theme() { return this.themeService.theme; }

  toggleTheme(): void {
    this.themeService.toggle();
  }

  ngOnInit(): void {
    this.loadModels();
    this.loadProjects();
  }

  loadModels(): void {
    this.modelsLoading.set(true);
    this.modelsError.set(null);

    this.ollamaService.getModels().subscribe({
      next: (models) => {
        this.models.set(models);
        this.modelsLoading.set(false);
      },
      error: (err) => {
        const message = err?.error?.message || 'Failed to load models. Is Ollama running?';
        this.modelsError.set(message);
        this.modelsLoading.set(false);
      }
    });
  }

  loadProjects(): void {
    const projectSummaries = this.projectStore.getProjects();

    // Convert ProjectSummary to Project format with empty files (will be populated on selection)
    const projects: Project[] = projectSummaries.map(ps => ({
      id: ps.id,
      name: ps.displayName,
      source: ps.source,
      fileCount: ps.fileCount,
      files: [], // Will be populated when project is selected
      indexingStatus: 'not_started' as const
    }));

    this.projects.set(projects);
  }

  setActiveModel(name: string): void {
    this.activeModel.set(name);
    localStorage.setItem('activeModel', name);
  }

  selectProject(project: Project): void {
    // For now, just set empty files since we don't have access to FileNode tree here
    // In a real implementation, we'd need to pass the tree or store it in ProjectStore
    this.selectedProject.set({
      ...project,
      files: [] // Placeholder - would need to flatten FileNode tree
    });
  }

  clearProjectSelection(): void {
    this.selectedProject.set(null);
  }

  selectActiveModel(modelName: string): void {
    this.activeModel.set(modelName);
    localStorage.setItem('deepcode_active_model', modelName);
  }

  isInstalled(modelName: string): boolean {
    // Check against real installed list from API (if we had it) OR our local simulated list
    // For this scaffold, we check both the models list (from API) and our local "installed" list
    const apiInstalled = this.models().some(m => m.name === modelName);
    const localInstalled = this.installedModels().includes(modelName);
    return apiInstalled || localInstalled;
  }

  isInstalling(modelName: string): boolean {
    return this.installingModels().has(modelName);
  }

  installModel(modelName: string): void {
    if (this.isInstalled(modelName) || this.isInstalling(modelName)) return;

    // Add to installing set
    this.installingModels.update(set => {
      const newSet = new Set(set);
      newSet.add(modelName);
      return newSet;
    });

    // Simulate install delay
    setTimeout(() => {
      // Remove from installing
      this.installingModels.update(set => {
        const newSet = new Set(set);
        newSet.delete(modelName);
        return newSet;
      });

      // Add to installed list
      this.installedModels.update(list => {
        const newList = [...list, modelName];
        localStorage.setItem('ollamaInstalledModels', JSON.stringify(newList));
        return newList;
      });
    }, 2000);
  }

  refreshModels(): void {
    this.loadModels();
  }

  /**
   * Start indexing a project using the backend IndexingService.
   * Gathers file contents from the ProjectStore and sends to API.
   */
  prepareIndexing(): void {
    const project = this.selectedProject();
    if (!project) return;

    console.log('[AdminComponent] Prepare indexing for:', project.name);
    console.log('[AdminComponent] Options:', this.indexingOptions());

    this.indexingError.set(null);

    // Get file tree from project store
    const projectData = this.projectStore.getProjectData(project.id);
    if (!projectData || !projectData.tree || projectData.tree.length === 0) {
      this.indexingError.set('Project data not found in store. Please re-import the project.');
      return;
    }

    // Flatten the file tree to get full ContextFile objects with github metadata
    const files: ContextFile[] = [];
    this.flattenFileTreeToContextFiles(projectData.tree, '', projectData.summary, files);

    if (files.length === 0) {
      this.indexingError.set('No files found to index');
      return;
    }

    console.log('[AdminComponent] Found', files.length, 'files for indexing');
    console.log('[AdminComponent] Files payload:', JSON.stringify(files.slice(0, 3), null, 2));

    // Update project status to in_progress
    const updated = this.projects().map(p =>
      p.id === project.id ? { ...p, indexingStatus: 'in_progress' as const } : p
    );
    this.projects.set(updated);
    this.selectedProject.set(updated.find(p => p.id === project.id) || null);

    // Start indexing - send full ContextFile objects with github metadata
    const options = this.indexingOptions();
    const request = {
      projectId: project.id,
      mode: 'selected' as const,
      files,
      embedModel: options.embedModel,
      chunkSize: options.chunkSize,
      chunkOverlap: options.overlap
    };
    console.log('[AdminComponent] Indexing request:', JSON.stringify(request, null, 2));

    this.indexingService.indexProject(request).subscribe({
      next: (response) => {
        console.log('[AdminComponent] Indexing started:', response);
        // Poll for status updates
        this.pollIndexingStatus(project.id);
      },
      error: (err) => {
        console.error('[AdminComponent] Failed to start indexing:', err);
        this.indexingError.set(err?.error?.message || 'Failed to start indexing');
        // Revert status
        const reverted = this.projects().map(p =>
          p.id === project.id ? { ...p, indexingStatus: 'error' as const } : p
        );
        this.projects.set(reverted);
        this.selectedProject.set(reverted.find(p => p.id === project.id) || null);
      }
    });
  }

  /**
   * Poll for indexing status updates.
   */
  private pollIndexingStatus(projectId: string): void {
    this.indexingService.pollStatus(projectId, 2000).subscribe({
      next: (status) => {
        console.log('[AdminComponent] Index status:', status);
        this.indexingStatus.set(status);

        // Map backend status to UI status
        const statusMap: Record<string, 'not_started' | 'in_progress' | 'completed' | 'error'> = {
          'PENDING': 'not_started',
          'IN_PROGRESS': 'in_progress',
          'COMPLETED': 'completed',
          'COMPLETED_WITH_ERRORS': 'completed',
          'FAILED': 'error'
        };
        const newStatus = statusMap[status.status] || 'not_started';
        const updated = this.projects().map(p =>
          p.id === projectId ? { ...p, indexingStatus: newStatus } : p
        );
        this.projects.set(updated);
        this.selectedProject.set(updated.find(p => p.id === projectId) || null);

        if (status.status === 'FAILED') {
          this.indexingError.set(status.errorMessage || 'Indexing failed');
        }
      },
      error: (err) => {
        console.error('[AdminComponent] Status polling error:', err);
        this.indexingError.set('Failed to get indexing status');
      }
    });
  }

  /**
   * Flatten the file tree to collect full ContextFile objects with github metadata.
   */
  private flattenFileTreeToContextFiles(
    nodes: FileNode[],
    prefix: string,
    projectSummary: ProjectSummary,
    result: ContextFile[]
  ): void {
    for (const node of nodes) {
      const path = prefix ? `${prefix}/${node.name}` : node.name;

      if (node.type === 'file') {
        const source = node.source || projectSummary.source;
        const contextFile: ContextFile = {
          source,
          path
        };

        // Add github metadata for github files
        if (source === 'github') {
          // Use node-level github metadata if available, fallback to project-level
          const githubMeta = node.githubMeta || {
            owner: projectSummary.owner,
            repo: projectSummary.repo,
            branch: projectSummary.branch,
            subPath: projectSummary.subPath
          };

          if (githubMeta.owner && githubMeta.repo) {
            contextFile.github = {
              owner: githubMeta.owner,
              repo: githubMeta.repo,
              branch: githubMeta.branch,
              subPath: githubMeta.subPath
            };
          }
        }

        result.push(contextFile);
      }

      if (node.children) {
        this.flattenFileTreeToContextFiles(node.children, path, projectSummary, result);
      }
    }
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'selected': return 'status-active';
      case 'active': return 'status-active';
      case 'installed': return 'status-installed';
      case 'indexed':
      case 'completed':
      case 'COMPLETED':
      case 'COMPLETED_WITH_ERRORS': return 'status-active';
      case 'in_progress':
      case 'IN_PROGRESS': return 'status-installing';
      case 'pending':
      case 'PENDING':
      case 'not_started': return 'status-inactive';
      case 'error':
      case 'FAILED': return 'status-error';
      default: return '';
    }
  }
}
