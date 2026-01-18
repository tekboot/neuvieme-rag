import { Injectable, signal } from '@angular/core';
import { FileNode } from '../models';

export interface ProjectSummary {
    id: string;
    source: 'device' | 'github';
    displayName: string;
    owner?: string;
    repo?: string;
    branch?: string;
    subPath?: string;
    fileCount: number;
    lastUpdated: number;
}

export interface ProjectData {
    summary: ProjectSummary;
    tree: FileNode[];
}

/**
 * Singleton service to track imported projects (device + GitHub).
 * Used by Admin page to show real projects instead of fake data.
 * Also stores file trees for indexing purposes.
 */
@Injectable({ providedIn: 'root' })
export class ProjectStore {
    private projects = signal<ProjectSummary[]>([]);
    private projectTrees = new Map<string, FileNode[]>();

    /**
     * Add or update a project in the store
     */
    addOrUpdateProject(project: ProjectSummary, tree?: FileNode[]): void {
        const existing = this.projects().find(p => p.id === project.id);

        if (existing) {
            // Update existing project
            this.projects.set(
                this.projects().map(p => p.id === project.id ? project : p)
            );
        } else {
            // Add new project
            this.projects.set([...this.projects(), project]);
        }

        // Store the file tree if provided
        if (tree) {
            this.projectTrees.set(project.id, tree);
        }
    }

    /**
     * Remove a project from the store
     */
    removeProject(id: string): void {
        this.projects.set(this.projects().filter(p => p.id !== id));
        this.projectTrees.delete(id);
    }

    /**
     * Get all tracked projects
     */
    getProjects(): ProjectSummary[] {
        return this.projects();
    }

    /**
     * Get a specific project by ID
     */
    getProjectById(id: string): ProjectSummary | null {
        return this.projects().find(p => p.id === id) || null;
    }

    /**
     * Get projects signal for reactive updates
     */
    getProjectsSignal() {
        return this.projects;
    }

    /**
     * Get the file tree for a project
     */
    getProjectTree(id: string): FileNode[] | null {
        return this.projectTrees.get(id) || null;
    }

    /**
     * Get project data (summary + tree)
     */
    getProjectData(id: string): ProjectData | null {
        const summary = this.projects().find(p => p.id === id);
        const tree = this.projectTrees.get(id);
        if (!summary) return null;
        return { summary, tree: tree || [] };
    }
}
