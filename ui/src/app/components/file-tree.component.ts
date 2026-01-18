import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FileNode } from '../models';

@Component({
  selector: 'app-file-tree',
  standalone: true,
  imports: [CommonModule],
  template: `
  <div class="tree">
    <ng-container *ngFor="let n of nodes">
      <div class="row" [class.selected]="n.path === selectedPath">
        <!-- ✅ Checkbox for file selection (only for files, only when enabled) -->
        <input
          type="checkbox"
          class="file-checkbox"
          *ngIf="selectionEnabled && n.type === 'file'"
          [checked]="n.selected || false"
          (click)="$event.stopPropagation()"
          (change)="onToggleSelect(n)"
        />

        <button class="row-main" (click)="onClick(n)">
          <span class="caret" *ngIf="n.type === 'folder'">
            {{ n.expanded ? '▾' : '▸' }}
          </span>
          <span class="caret" *ngIf="n.type !== 'folder' && !selectionEnabled"></span>

          <span class="icon">{{ n.type === 'folder' ? '📁' : '📄' }}</span>
          <span class="name">{{ n.name }}</span>
        </button>

        <!-- ✅ Refresh button for GitHub root folders -->
        <button
          class="mini"
          *ngIf="n.type === 'folder' && n.source === 'github' && n.githubMeta"
          (click)="onRefresh(n); $event.stopPropagation()"
          title="Refresh GitHub repo"
        >
          ⟳
        </button>
      </div>

      <div class="children" *ngIf="n.type === 'folder' && n.expanded">
        <app-file-tree
          [nodes]="n.children || []"
          [selectedPath]="selectedPath"
          [selectionEnabled]="selectionEnabled"
          (selectFile)="selectFile.emit($event)"
          (toggleSelect)="toggleSelect.emit($event)"
          (refreshGithub)="refreshGithub.emit($event)"
        ></app-file-tree>
      </div>
    </ng-container>
  </div>
  `,
  styles: [`
    .tree { display:flex; flex-direction:column; gap: 2px; }
    .row { display:flex; align-items:center; gap: 6px; border-radius: 8px; }
    /* Selected state: use accent background with theme-appropriate opacity */
    .row.selected { background: var(--accent-bg); }

    .row-main{
      flex: 1;
      display:flex;
      align-items:center;
      gap: 8px;
      padding: 6px 8px;
      border: none;
      background: transparent;
      color: var(--text-primary);
      cursor: pointer;
      text-align: left;
      border-radius: 8px;
    }
    .row-main:hover { background: var(--bg-secondary); }

    .caret { width: 16px; opacity: 0.8; }
    .icon { width: 18px; opacity: 0.9; }
    .name { font-size: 13px; }

    .mini{
      background: var(--bg-secondary);
      border: 1px solid var(--border-light);
      color: var(--text-primary);
      border-radius: 8px;
      padding: 4px 8px;
      cursor: pointer;
      font-size: 12px;
    }
    .mini:hover { background: var(--btn-hover); }

    .children { padding-left: 16px; }

    .file-checkbox {
      width: 14px;
      height: 14px;
      margin: 0 0 0 8px;
      cursor: pointer;
      accent-color: var(--accent);
    }
  `]
})
export class FileTreeComponent {
  @Input() nodes: FileNode[] = [];
  @Input() selectedPath: string | null = null;
  @Input() selectionEnabled = false;

  @Output() selectFile = new EventEmitter<FileNode>();
  @Output() toggleSelect = new EventEmitter<FileNode>();
  @Output() refreshGithub = new EventEmitter<FileNode>();

  onClick(n: FileNode) {
    if (n.type === 'folder') n.expanded = !n.expanded;
    this.selectFile.emit(n);
  }

  onToggleSelect(n: FileNode) {
    this.toggleSelect.emit(n);
  }

  onRefresh(n: FileNode) {
    this.refreshGithub.emit(n);
  }
}
