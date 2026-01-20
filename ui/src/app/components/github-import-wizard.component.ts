import { Component, EventEmitter, Output, Input, signal, computed, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { GithubImportService, GithubRepoInfo, GithubImportResponse, GithubBranch } from '../services/github-import.service';
import { AuthService } from '../services/auth.service';
import { ModelsService, ModelInfo } from '../services/models.service';
import { IndexingService, IndexStatusResponse } from '../services/indexing.service';

type WizardStep = 'auth' | 'select-repo' | 'options';
type IndexMode = 'PREINDEX' | 'LAZY';

@Component({
  selector: 'app-github-import-wizard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="backdrop" (click)="close.emit()"></div>
    <div class="modal wizard-modal">
      <!-- Header with stepper -->
      <div class="modal-header">
        <div class="modal-title">Import from GitHub</div>
        <button class="icon-btn" (click)="close.emit()">✕</button>
      </div>

      <!-- Stepper -->
      <div class="stepper">
        <div class="step" [class.active]="currentStep() === 'auth'" [class.completed]="stepCompleted('auth')">
          <div class="step-num">1</div>
          <div class="step-label">Authorize</div>
        </div>
        <div class="step-line" [class.active]="stepCompleted('auth')"></div>
        <div class="step" [class.active]="currentStep() === 'select-repo'" [class.completed]="stepCompleted('select-repo')">
          <div class="step-num">2</div>
          <div class="step-label">Select Repo</div>
        </div>
        <div class="step-line" [class.active]="stepCompleted('select-repo')"></div>
        <div class="step" [class.active]="currentStep() === 'options'">
          <div class="step-num">3</div>
          <div class="step-label">Options</div>
        </div>
      </div>

      <!-- Modal Body -->
      <div class="modal-body">

        <!-- Step 1: Auth Screen -->
        <div class="step-content" *ngIf="currentStep() === 'auth'">
          <!-- Loading state while checking auth -->
          <div class="loading-state" *ngIf="checkingAuth()">
            <div class="spinner"></div>
            <span>Checking GitHub connection...</span>
          </div>

          <!-- Auth form -->
          <div class="auth-form" *ngIf="!checkingAuth()">
            <div class="auth-banner" *ngIf="!githubConnected()">
              <span class="auth-icon">🔒</span>
              <div class="auth-content">
                <div class="auth-title">GitHub Authorization Required</div>
                <div class="auth-desc">
                  Connect your GitHub account to browse and import repositories.
                </div>
              </div>
            </div>

            <div class="auth-banner success" *ngIf="githubConnected()">
              <span class="auth-icon">✓</span>
              <div class="auth-content">
                <div class="auth-title">GitHub Connected</div>
                <div class="auth-desc">
                  You're authenticated. Enter a username or organization to browse repositories.
                </div>
              </div>
            </div>

            <label class="field">
              <span>GitHub Username or Organization</span>
              <input
                class="text"
                [(ngModel)]="githubOwner"
                placeholder="e.g. facebook, microsoft, your-username"
                (keydown.enter)="githubConnected() ? fetchRepos() : authorizeGithub()"
              />
              <span class="field-hint">Leave empty to browse your own repositories</span>
            </label>

            <div class="auth-info" *ngIf="!githubConnected()">
              <div class="info-item">
                <span class="check">✓</span>
                <span>Access your public and private repositories</span>
              </div>
              <div class="info-item">
                <span class="check">✓</span>
                <span>Import code for AI-powered analysis</span>
              </div>
              <div class="info-item">
                <span class="check">✓</span>
                <span>Secure OAuth authentication</span>
              </div>
            </div>

            <div class="error-box" *ngIf="error()">
              <span class="error-icon">⚠️</span>
              <div class="error-content">
                <div class="error-msg">{{ error() }}</div>
              </div>
              <button class="btn-link" (click)="recheckAuth()">Retry</button>
            </div>
          </div>
        </div>

        <!-- Step 2: Select Repository -->
        <div class="step-content" *ngIf="currentStep() === 'select-repo'">
          <p class="step-description">
            Select a repository{{ githubOwner ? ' from ' + githubOwner : '' }}
          </p>

          <!-- Search/Filter -->
          <div class="search-box">
            <input
              class="text search-input"
              [(ngModel)]="repoFilter"
              placeholder="Search repositories..."
            />
          </div>

          <!-- Repo List -->
          <div class="repo-list" *ngIf="!loadingRepos() && filteredRepos().length > 0">
            <div
              class="repo-item"
              *ngFor="let repo of filteredRepos()"
              [class.selected]="selectedRepo()?.fullName === repo.fullName"
              (click)="selectRepo(repo)"
            >
              <div class="repo-main">
                <div class="repo-name">
                  {{ repo.fullName }}
                  <span class="private-badge" *ngIf="repo.isPrivate">Private</span>
                </div>
                <div class="repo-desc" *ngIf="repo.description">{{ repo.description }}</div>
              </div>
              <div class="repo-meta">
                <span class="repo-lang" *ngIf="repo.language">{{ repo.language }}</span>
                <span class="repo-stars" *ngIf="repo.stargazersCount > 0">⭐ {{ repo.stargazersCount }}</span>
              </div>
            </div>
          </div>

          <div class="empty-state" *ngIf="!loadingRepos() && filteredRepos().length === 0 && repos().length > 0">
            No repositories match "{{ repoFilter }}"
          </div>

          <div class="empty-state" *ngIf="!loadingRepos() && repos().length === 0">
            No repositories found. Make sure you have access to at least one repository.
          </div>

          <div class="loading-state" *ngIf="loadingRepos()">
            <div class="spinner"></div>
            <span>Loading repositories...</span>
          </div>

          <div class="helper-text" *ngIf="!selectedRepo() && !loadingRepos() && repos().length > 0">
            Select a repository to continue
          </div>

          <div class="error-box" *ngIf="error()">
            <span class="error-icon">⚠️</span>
            <div class="error-content">
              <div class="error-msg">{{ error() }}</div>
            </div>
          </div>
        </div>

        <!-- Step 3: Options -->
        <div class="step-content" *ngIf="currentStep() === 'options'">
          <p class="step-description">
            Configure import options for <strong>{{ selectedRepo()?.fullName }}</strong>
          </p>

          <!-- Branch Selector -->
          <label class="field">
            <span>Branch</span>
            <div class="select-wrapper">
              <select class="select" [(ngModel)]="selectedBranch" [disabled]="loadingBranches()">
                <option *ngIf="loadingBranches()" value="">Loading branches...</option>
                <option *ngFor="let branch of branches()" [value]="branch.name">{{ branch.name }}</option>
              </select>
              <div class="select-loading" *ngIf="loadingBranches()">
                <div class="spinner small"></div>
              </div>
            </div>
          </label>

          <!-- Sub-path (optional) -->
          <label class="field">
            <span>Sub-path (optional)</span>
            <input class="text" [(ngModel)]="subPath" placeholder="e.g. src/app" />
            <span class="field-hint">Import only files within this directory</span>
          </label>

          <!-- Embedding Model Selector -->
          <label class="field">
            <span>Embedding Model</span>
            <div class="select-wrapper">
              <select class="select" [(ngModel)]="selectedEmbedModel" [disabled]="loadingModels()">
                <option *ngIf="loadingModels()" value="">Loading models...</option>
                <option *ngFor="let model of embedModels()" [value]="model">{{ model }}</option>
              </select>
              <div class="select-loading" *ngIf="loadingModels()">
                <div class="spinner small"></div>
              </div>
            </div>
            <span class="field-hint">Model used for creating vector embeddings</span>
          </label>

          <!-- Indexing Timing -->
          <div class="field">
            <span>Indexing</span>
            <div class="radio-group">
              <label class="radio-option" [class.selected]="indexMode === 'PREINDEX'">
                <input type="radio" name="indexMode" value="PREINDEX" [(ngModel)]="indexMode" />
                <div class="radio-content">
                  <div class="radio-title">Index now</div>
                  <div class="radio-desc">
                    Fetch and index file contents immediately. RAG queries work right after import.
                  </div>
                </div>
              </label>

              <label class="radio-option" [class.selected]="indexMode === 'LAZY'">
                <input type="radio" name="indexMode" value="LAZY" [(ngModel)]="indexMode" />
                <div class="radio-content">
                  <div class="radio-title">Define context later</div>
                  <div class="radio-desc">
                    Import structure only. Select files to index manually later.
                  </div>
                </div>
              </label>
            </div>
          </div>

          <div class="error-box" *ngIf="error() && !indexingActive()">
            <span class="error-icon">⚠️</span>
            <div class="error-content">
              <div class="error-msg">{{ error() }}</div>
            </div>
          </div>

          <!-- Progress Overlay for Indexing -->
          <div class="progress-overlay" *ngIf="indexingActive()">
            <div class="progress-card">
              <div class="progress-header">
                <div class="progress-icon">
                  <div class="spinner large"></div>
                </div>
                <div class="progress-title">Indexing Repository</div>
                <div class="progress-subtitle">{{ selectedRepo()?.fullName }}</div>
              </div>

              <div class="progress-bar-container">
                <div class="progress-bar">
                  <div class="progress-fill" [style.width.%]="indexingProgress()"></div>
                </div>
                <div class="progress-percent">{{ indexingProgress() }}%</div>
              </div>

              <div class="progress-stats">
                <div class="stat">
                  <span class="stat-label">Files</span>
                  <span class="stat-value">{{ indexingStatus()?.indexedFiles || 0 }} / {{ indexingStatus()?.totalFiles || 0 }}</span>
                </div>
                <div class="stat">
                  <span class="stat-label">Chunks</span>
                  <span class="stat-value">{{ indexingStatus()?.totalChunks || 0 }}</span>
                </div>
              </div>

              <div class="progress-message">{{ indexingMessage() }}</div>
            </div>
          </div>

          <!-- Indexing Error Display -->
          <div class="error-box indexing-error" *ngIf="indexingFailed()">
            <span class="error-icon">⚠️</span>
            <div class="error-content">
              <div class="error-title">Indexing Failed</div>
              <div class="error-msg">{{ indexingStatus()?.errorMessage || 'An error occurred during indexing.' }}</div>
            </div>
            <button class="btn-link" (click)="retryImport()">Retry</button>
          </div>
        </div>

      </div>

      <!-- Footer -->
      <div class="modal-footer">
        <button class="btn secondary" (click)="close.emit()" [disabled]="indexingActive()">Cancel</button>

        <!-- Step 1: Auth buttons - ALWAYS show Authorize GitHub button -->
        <ng-container *ngIf="currentStep() === 'auth'">
          <!-- Show "Authorize GitHub" when not connected -->
          <button
            *ngIf="!githubConnected()"
            class="btn secondary"
            (click)="authorizeGithub()"
            [disabled]="checkingAuth()"
          >
            {{ checkingAuth() ? 'Checking...' : 'Authorize GitHub' }}
          </button>
          <!-- Show "Browse Repositories" when connected -->
          <button
            *ngIf="githubConnected()"
            class="btn secondary"
            (click)="fetchRepos()"
            [disabled]="loadingRepos()"
          >
            {{ loadingRepos() ? 'Loading...' : 'Browse Repositories' }}
          </button>
        </ng-container>

        <!-- Step 2: Select Repo buttons - ALWAYS show Next button -->
        <ng-container *ngIf="currentStep() === 'select-repo'">
          <button class="btn secondary" (click)="goToStep('auth')">Back</button>
          <button
            class="btn secondary"
            [disabled]="!selectedRepo() || loadingRepos()"
            (click)="goToOptions()"
          >
            Next
          </button>
        </ng-container>

        <!-- Step 3: Options buttons -->
        <ng-container *ngIf="currentStep() === 'options'">
          <button class="btn secondary" (click)="goToStep('select-repo')" [disabled]="importing() || indexingActive()">Back</button>
          <button
            class="btn secondary"
            [disabled]="importing() || loadingBranches() || indexingActive()"
            (click)="doImport()"
          >
            {{ getImportButtonLabel() }}
          </button>
        </ng-container>
      </div>
    </div>
  `,
  styles: [`
    .backdrop {
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.6);
      z-index: 100;
    }

    .wizard-modal {
      position: fixed;
      top: 50%;
      left: 50%;
      transform: translate(-50%, -50%);
      width: 560px;
      max-width: 95vw;
      max-height: 85vh;
      background: var(--bg-secondary);
      border: 1px solid var(--border-color);
      border-radius: 12px;
      z-index: 101;
      display: flex;
      flex-direction: column;
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
    }

    .modal-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 16px 20px;
      border-bottom: 1px solid var(--border-color);
    }

    .modal-title {
      font-size: 16px;
      font-weight: 600;
    }

    .icon-btn {
      background: transparent;
      border: none;
      color: var(--text-secondary);
      cursor: pointer;
      font-size: 18px;
      padding: 4px 8px;
      border-radius: 4px;
    }

    .icon-btn:hover {
      background: var(--btn-hover);
    }

    /* Stepper */
    .stepper {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 16px 20px;
      gap: 8px;
      border-bottom: 1px solid var(--border-color);
      background: var(--bg-tertiary);
    }

    .step {
      display: flex;
      align-items: center;
      gap: 8px;
      opacity: 0.5;
    }

    .step.active, .step.completed {
      opacity: 1;
    }

    .step-num {
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: var(--border-color);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 12px;
      font-weight: 600;
    }

    .step.active .step-num {
      background: var(--accent-color);
      color: white;
    }

    .step.completed .step-num {
      background: var(--success-color, #22c55e);
      color: white;
    }

    .step-label {
      font-size: 13px;
    }

    .step-line {
      width: 40px;
      height: 2px;
      background: var(--border-color);
    }

    .step-line.active {
      background: var(--success-color, #22c55e);
    }

    /* Modal Body */
    .modal-body {
      flex: 1;
      overflow-y: auto;
      padding: 20px;
    }

    .step-content {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .step-description {
      color: var(--text-secondary);
      font-size: 14px;
      margin: 0;
    }

    /* Auth Screen */
    .auth-form {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .auth-banner {
      display: flex;
      align-items: flex-start;
      gap: 16px;
      padding: 16px;
      background: linear-gradient(135deg, rgba(59, 130, 246, 0.1) 0%, rgba(139, 92, 246, 0.1) 100%);
      border: 1px solid rgba(59, 130, 246, 0.3);
      border-radius: 10px;
    }

    .auth-banner.success {
      background: linear-gradient(135deg, rgba(34, 197, 94, 0.1) 0%, rgba(16, 185, 129, 0.1) 100%);
      border-color: rgba(34, 197, 94, 0.3);
    }

    .auth-banner.success .auth-icon {
      color: var(--success-color, #22c55e);
    }

    .auth-icon {
      font-size: 28px;
    }

    .auth-content {
      flex: 1;
    }

    .auth-title {
      font-size: 15px;
      font-weight: 600;
      margin-bottom: 4px;
    }

    .auth-desc {
      font-size: 13px;
      color: var(--text-secondary);
      line-height: 1.4;
    }

    .auth-info {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 0 4px;
    }

    .info-item {
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 13px;
      color: var(--text-secondary);
    }

    .info-item .check {
      color: var(--success-color, #22c55e);
      font-weight: bold;
    }

    /* Fields */
    .field {
      display: flex;
      flex-direction: column;
      gap: 6px;
    }

    .field > span:first-child {
      font-size: 13px;
      font-weight: 500;
      color: var(--text-primary);
    }

    .field-hint {
      font-size: 12px;
      color: var(--text-secondary);
    }

    .text {
      background: var(--bg-primary);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      padding: 10px 12px;
      color: var(--text-primary);
      font-size: 14px;
    }

    .text:focus {
      outline: none;
      border-color: var(--accent-color);
    }

    .select-wrapper {
      position: relative;
    }

    .select {
      width: 100%;
      background: var(--bg-primary);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      padding: 10px 12px;
      color: var(--text-primary);
      font-size: 14px;
      cursor: pointer;
      appearance: none;
    }

    .select:focus {
      outline: none;
      border-color: var(--accent-color);
    }

    .select:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }

    .select-loading {
      position: absolute;
      right: 12px;
      top: 50%;
      transform: translateY(-50%);
    }

    /* Error box */
    .error-box {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      padding: 12px;
      background: rgba(239, 68, 68, 0.1);
      border: 1px solid rgba(239, 68, 68, 0.3);
      border-radius: 8px;
    }

    .error-icon {
      font-size: 16px;
    }

    .error-content {
      flex: 1;
    }

    .error-msg {
      color: var(--error-color, #ef4444);
      font-size: 13px;
    }

    .btn-link {
      background: none;
      border: none;
      color: var(--accent-color);
      cursor: pointer;
      font-size: 13px;
      text-decoration: underline;
    }

    /* Search box */
    .search-box {
      margin-bottom: 8px;
    }

    .search-input {
      width: 100%;
    }

    /* Repo list */
    .repo-list {
      max-height: 260px;
      overflow-y: auto;
      border: 1px solid var(--border-color);
      border-radius: 8px;
    }

    .repo-item {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      padding: 12px;
      cursor: pointer;
      border-bottom: 1px solid var(--border-color);
    }

    .repo-item:last-child {
      border-bottom: none;
    }

    .repo-item:hover {
      background: var(--btn-hover);
    }

    .repo-item.selected {
      background: rgba(59, 130, 246, 0.15);
      border-left: 3px solid var(--accent-color);
    }

    .repo-main {
      flex: 1;
      min-width: 0;
    }

    .repo-name {
      font-weight: 500;
      font-size: 14px;
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .private-badge {
      font-size: 10px;
      background: var(--bg-tertiary);
      padding: 2px 6px;
      border-radius: 4px;
      font-weight: normal;
    }

    .repo-desc {
      font-size: 12px;
      color: var(--text-secondary);
      margin-top: 4px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .repo-meta {
      display: flex;
      gap: 10px;
      font-size: 11px;
      color: var(--text-secondary);
    }

    .helper-text {
      text-align: center;
      font-size: 13px;
      color: var(--text-secondary);
      padding: 8px;
    }

    /* Empty/Loading states */
    .empty-state, .loading-state {
      padding: 40px 20px;
      text-align: center;
      color: var(--text-secondary);
      font-size: 14px;
    }

    .loading-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 12px;
    }

    .spinner {
      width: 24px;
      height: 24px;
      border: 2px solid var(--border-color);
      border-top-color: var(--accent-color);
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    .spinner.small {
      width: 16px;
      height: 16px;
      border-width: 2px;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    /* Radio group */
    .radio-group {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .radio-option {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 12px;
      border: 1px solid var(--border-color);
      border-radius: 8px;
      cursor: pointer;
    }

    .radio-option:hover {
      background: var(--btn-hover);
    }

    .radio-option.selected {
      border-color: var(--accent-color);
      background: rgba(59, 130, 246, 0.08);
    }

    .radio-option input[type="radio"] {
      margin-top: 2px;
    }

    .radio-content {
      flex: 1;
    }

    .radio-title {
      font-weight: 500;
      font-size: 14px;
    }

    .radio-desc {
      font-size: 12px;
      color: var(--text-secondary);
      margin-top: 4px;
      line-height: 1.4;
    }

    /* Footer */
    .modal-footer {
      display: flex;
      justify-content: flex-end;
      gap: 10px;
      padding: 16px 20px;
      border-top: 1px solid var(--border-color);
    }

    .btn {
      padding: 8px 16px;
      border-radius: 6px;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
      border: none;
    }

    .btn.primary {
      background: var(--accent-color);
      color: white;
    }

    .btn.primary:hover:not(:disabled) {
      opacity: 0.9;
    }

    .btn.secondary {
      background: var(--bg-tertiary);
      color: var(--text-primary);
      border: 1px solid var(--border-color);
    }

    .btn.secondary:hover:not(:disabled) {
      background: var(--btn-hover);
    }

    .btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    /* Progress Overlay */
    .progress-overlay {
      position: absolute;
      inset: 0;
      background: var(--bg-secondary);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 10;
      border-radius: 8px;
    }

    .progress-card {
      width: 100%;
      max-width: 380px;
      padding: 24px;
      text-align: center;
    }

    .progress-header {
      margin-bottom: 24px;
    }

    .progress-icon {
      display: flex;
      justify-content: center;
      margin-bottom: 16px;
    }

    .spinner.large {
      width: 48px;
      height: 48px;
      border-width: 3px;
    }

    .progress-title {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-primary);
      margin-bottom: 4px;
    }

    .progress-subtitle {
      font-size: 13px;
      color: var(--text-secondary);
    }

    .progress-bar-container {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 20px;
    }

    .progress-bar {
      flex: 1;
      height: 8px;
      background: var(--border-color);
      border-radius: 4px;
      overflow: hidden;
    }

    .progress-fill {
      height: 100%;
      background: linear-gradient(90deg, var(--accent-color) 0%, #22c55e 100%);
      border-radius: 4px;
      transition: width 0.3s ease;
    }

    .progress-percent {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-primary);
      min-width: 45px;
      text-align: right;
    }

    .progress-stats {
      display: flex;
      justify-content: center;
      gap: 32px;
      margin-bottom: 16px;
    }

    .stat {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .stat-label {
      font-size: 11px;
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .stat-value {
      font-size: 14px;
      font-weight: 500;
      color: var(--text-primary);
    }

    .progress-message {
      font-size: 13px;
      color: var(--text-secondary);
      padding: 12px;
      background: var(--bg-tertiary);
      border-radius: 6px;
    }

    /* Indexing error styling */
    .indexing-error {
      margin-top: 16px;
    }

    .error-title {
      font-weight: 600;
      margin-bottom: 4px;
      color: var(--error-color, #ef4444);
    }

    /* Make step-content relative for overlay positioning */
    .step-content {
      position: relative;
      min-height: 200px;
    }
  `]
})
export class GithubImportWizardComponent implements OnInit, OnDestroy {
  @Input() isOAuthReturn = false; // Set to true when opening after OAuth redirect
  @Output() close = new EventEmitter<void>();
  @Output() imported = new EventEmitter<GithubImportResponse>();

  // State
  currentStep = signal<WizardStep>('auth');
  checkingAuth = signal(false); // Start as false - only true when actively checking
  githubConnected = signal(false);
  error = signal<string | null>(null);

  // Step 1: Auth
  githubOwner = '';

  // Step 2: Select Repo
  loadingRepos = signal(false);
  repos = signal<GithubRepoInfo[]>([]);
  selectedRepo = signal<GithubRepoInfo | null>(null);
  repoFilter = '';

  // Step 3: Options
  loadingBranches = signal(false);
  branches = signal<GithubBranch[]>([]);
  selectedBranch = '';
  subPath = '';
  loadingModels = signal(false);
  embedModels = signal<string[]>(['nomic-embed-text', 'mxbai-embed-large', 'all-minilm']);
  selectedEmbedModel = 'nomic-embed-text';
  indexMode: IndexMode = 'PREINDEX';
  importing = signal(false);

  // Indexing Progress State
  indexingActive = signal(false);
  indexingFailed = signal(false);
  indexingStatus = signal<IndexStatusResponse | null>(null);
  private indexingSubscription: Subscription | null = null;
  private pendingImportResponse: GithubImportResponse | null = null;

  // Computed: Progress percentage (0-100)
  indexingProgress = computed(() => {
    const status = this.indexingStatus();
    return status?.progress ?? 0;
  });

  // Computed: Human-readable message
  indexingMessage = computed(() => {
    const status = this.indexingStatus();
    return status?.message ?? 'Preparing to index...';
  });

  // Computed
  filteredRepos = computed(() => {
    const filter = this.repoFilter.toLowerCase().trim();
    if (!filter) return this.repos();
    return this.repos().filter(r =>
      r.name.toLowerCase().includes(filter) ||
      r.fullName.toLowerCase().includes(filter) ||
      (r.description?.toLowerCase().includes(filter))
    );
  });

  constructor(
    private githubService: GithubImportService,
    private auth: AuthService,
    private modelsService: ModelsService,
    private indexingService: IndexingService
  ) { }

  ngOnInit() {
    console.log('[GithubWizard] Initializing wizard, isOAuthReturn:', this.isOAuthReturn);

    // Restore owner draft from localStorage if it was saved before OAuth
    const savedOwner = localStorage.getItem('github_import_owner_draft');
    if (savedOwner) {
      this.githubOwner = savedOwner;
      localStorage.removeItem('github_import_owner_draft');
    }

    if (this.isOAuthReturn) {
      // Returning from OAuth redirect - check auth status
      console.log('[GithubWizard] Returning from OAuth, checking auth...');
      this.checkAuthStatus();
    } else {
      // Fresh modal open - don't auto-check auth, just show the auth step
      console.log('[GithubWizard] Fresh open, showing auth step (no auto-check)');
      this.checkingAuth.set(false);
      this.githubConnected.set(false);
    }
  }

  checkAuthStatus() {
    this.checkingAuth.set(true);
    this.error.set(null);

    this.auth.me().subscribe({
      next: (state) => {
        console.log('[GithubWizard] Auth state:', state);
        this.checkingAuth.set(false);
        this.githubConnected.set(state.githubAuthenticated);

        if (state.githubAuthenticated) {
          // Auto-advance to Step 2 (fetch repos) if authenticated
          console.log('[GithubWizard] GitHub authenticated, auto-advancing to repo selection...');
          this.fetchRepos();
        }
      },
      error: (err) => {
        console.error('[GithubWizard] Auth check failed:', err);
        this.checkingAuth.set(false);
        this.githubConnected.set(false);

        if (err.status === 401) {
          this.error.set('Authentication required. Please authorize GitHub to continue.');
        } else {
          this.error.set('Failed to check authentication status. Please try again.');
        }
      }
    });
  }

  recheckAuth() {
    this.checkAuthStatus();
  }

  authorizeGithub() {
    localStorage.setItem('openGithubModalAfterLogin', '1');
    if (this.githubOwner.trim()) {
      localStorage.setItem('github_import_owner_draft', this.githubOwner.trim());
    }
    this.auth.connectGithub();
  }

  fetchRepos() {
    this.loadingRepos.set(true);
    this.error.set(null);

    const owner = this.githubOwner.trim() || undefined;

    this.githubService.listRepos(owner).subscribe({
      next: (repos) => {
        console.log('[GithubWizard] Loaded repos:', repos.length);
        this.loadingRepos.set(false);
        this.repos.set(repos);
        this.currentStep.set('select-repo');
      },
      error: (err) => {
        console.error('[GithubWizard] Failed to load repos:', err);
        this.loadingRepos.set(false);

        const errBody = err.error || {};
        const code = errBody.code || '';
        const message = errBody.message || err.message || 'Failed to load repositories';

        if (err.status === 401 || code === 'GITHUB_AUTH_MISSING' || code === 'GITHUB_TOKEN_MISSING') {
          this.githubConnected.set(false);
          this.error.set('Authentication required. Please authorize GitHub.');
        } else {
          this.error.set(message);
        }
      }
    });
  }

  stepCompleted(step: WizardStep): boolean {
    const steps: WizardStep[] = ['auth', 'select-repo', 'options'];
    const currentIndex = steps.indexOf(this.currentStep());
    const stepIndex = steps.indexOf(step);
    return stepIndex < currentIndex;
  }

  goToStep(step: WizardStep) {
    this.error.set(null);
    this.currentStep.set(step);
  }

  selectRepo(repo: GithubRepoInfo) {
    this.selectedRepo.set(repo);
    this.selectedBranch = repo.defaultBranch;
  }

  goToOptions() {
    const repo = this.selectedRepo();
    if (!repo) return;

    this.currentStep.set('options');
    this.loadBranches(repo);
    this.loadEmbedModels();
  }

  loadBranches(repo: GithubRepoInfo) {
    this.loadingBranches.set(true);

    const [owner, repoName] = repo.fullName.split('/');

    this.githubService.listBranches(owner, repoName).subscribe({
      next: (branches) => {
        console.log('[GithubWizard] Loaded branches:', branches.length);
        this.loadingBranches.set(false);
        this.branches.set(branches);

        // Set default branch if not already set
        if (!this.selectedBranch && branches.length > 0) {
          const defaultBranch = branches.find(b => b.name === repo.defaultBranch);
          this.selectedBranch = defaultBranch?.name || branches[0].name;
        }
      },
      error: (err) => {
        console.error('[GithubWizard] Failed to load branches:', err);
        this.loadingBranches.set(false);
        // Fall back to default branch from repo info
        this.branches.set([{ name: repo.defaultBranch }]);
        this.selectedBranch = repo.defaultBranch;
      }
    });
  }

  loadEmbedModels() {
    this.loadingModels.set(true);

    this.modelsService.listModels().subscribe({
      next: (models) => {
        this.loadingModels.set(false);
        // Filter for embedding models (typically have "embed" in name)
        const embedModels = models
          .filter(m => m.name.toLowerCase().includes('embed') || m.name.toLowerCase().includes('minilm'))
          .map(m => m.name);

        if (embedModels.length > 0) {
          this.embedModels.set(embedModels);
          if (!embedModels.includes(this.selectedEmbedModel)) {
            this.selectedEmbedModel = embedModels[0];
          }
        }
      },
      error: () => {
        this.loadingModels.set(false);
        // Keep default models on error
      }
    });
  }

  getImportButtonLabel(): string {
    if (this.importing()) return 'Importing...';
    return this.indexMode === 'PREINDEX' ? 'Import & Index' : 'Import';
  }

  doImport() {
    const repo = this.selectedRepo();
    if (!repo) return;

    const [owner, repoName] = repo.fullName.split('/');

    this.importing.set(true);
    this.error.set(null);
    this.indexingFailed.set(false);

    const payload = {
      owner: owner,
      repo: repoName,
      branch: this.selectedBranch || repo.defaultBranch,
      subPath: this.subPath || undefined,
      indexMode: this.indexMode,
      embedModel: this.selectedEmbedModel
    };

    console.log('[GithubWizard] Starting import:', payload);

    this.githubService.importRepo(payload).subscribe({
      next: (res) => {
        console.log('[GithubWizard] Import response:', res);
        this.importing.set(false);

        // If indexing was started (PREINDEX mode), show progress and poll
        if (res.indexingStarted && res.project?.id) {
          console.log('[GithubWizard] Indexing started, beginning progress polling...');
          this.pendingImportResponse = res;
          this.indexingActive.set(true);
          this.startIndexingPoll(res.project.id);
        } else {
          // No indexing (LAZY mode) - emit immediately
          this.imported.emit(res);
        }
      },
      error: (err) => {
        console.error('[GithubWizard] Import failed:', err);
        this.importing.set(false);
        const errBody = err.error || {};
        const message = errBody.message || err.message || 'Import failed';
        this.error.set(message);
      }
    });
  }

  private startIndexingPoll(projectId: string) {
    // Clean up any existing subscription
    this.stopIndexingPoll();

    console.log('[GithubWizard] Starting indexing poll for project:', projectId);

    this.indexingSubscription = this.indexingService.pollStatus(projectId, 1000).subscribe({
      next: (status) => {
        console.log('[GithubWizard] Indexing status:', status.status, status.progress + '%');
        this.indexingStatus.set(status);

        // Check if completed
        if (status.status === 'COMPLETED' || status.status === 'COMPLETED_WITH_ERRORS') {
          console.log('[GithubWizard] Indexing completed successfully!');
          this.indexingActive.set(false);
          this.stopIndexingPoll();

          // Emit the pending import response
          if (this.pendingImportResponse) {
            this.imported.emit(this.pendingImportResponse);
            this.pendingImportResponse = null;
          }
        } else if (status.status === 'FAILED') {
          console.error('[GithubWizard] Indexing failed:', status.errorMessage);
          this.indexingActive.set(false);
          this.indexingFailed.set(true);
          this.stopIndexingPoll();
        }
      },
      error: (err) => {
        console.error('[GithubWizard] Indexing poll error:', err);
        this.indexingActive.set(false);
        this.indexingFailed.set(true);
        this.error.set('Failed to track indexing progress: ' + (err.message || 'Unknown error'));
        this.stopIndexingPoll();
      }
    });
  }

  private stopIndexingPoll() {
    if (this.indexingSubscription) {
      this.indexingSubscription.unsubscribe();
      this.indexingSubscription = null;
    }
  }

  retryImport() {
    // Reset state and retry
    this.indexingFailed.set(false);
    this.indexingStatus.set(null);
    this.error.set(null);
    this.pendingImportResponse = null;
    this.doImport();
  }

  ngOnDestroy() {
    this.stopIndexingPoll();
  }
}
