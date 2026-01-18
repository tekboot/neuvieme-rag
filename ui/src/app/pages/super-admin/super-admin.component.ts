import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { OllamaService, OllamaModel } from '../../services/ollama.service';
import { ThemeService } from '../../services/theme.service';

@Component({
  selector: 'app-super-admin',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './super-admin.component.html',
  styleUrls: ['./super-admin.component.scss']
})
export class SuperAdminComponent implements OnInit {
  models = signal<OllamaModel[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);

  // Active model selection (stored in localStorage)
  activeModel = signal<string>(localStorage.getItem('activeModel') || 'qwen2.5-coder');

  // Pull model form
  pullModelName = '';
  pulling = signal(false);
  pullError = signal<string | null>(null);
  pullSuccess = signal<string | null>(null);

  // Mock users for now
  users = signal([
    { id: 1, name: 'Admin User', email: 'admin@example.com', role: 'admin' },
    { id: 2, name: 'Regular User', email: 'user@example.com', role: 'user' },
    { id: 3, name: 'Super Admin', email: 'super@example.com', role: 'super-admin' },
  ]);

  // User Management
  editModalOpen = signal(false);
  deleteModalOpen = signal(false);
  selectedUser = signal<any>(null);

  // Edit Form
  editForm = signal({ name: '', email: '', role: 'user' });

  constructor(
    private ollamaService: OllamaService,
    public themeService: ThemeService
  ) { }

  get theme() { return this.themeService.theme; }

  toggleTheme(): void {
    this.themeService.toggle();
  }

  ngOnInit(): void {
    this.loadModels();
  }

  // User Actions
  openEditModal(user: any): void {
    this.selectedUser.set(user);
    this.editForm.set({ ...user });
    this.editModalOpen.set(true);
  }

  closeEditModal(): void {
    this.editModalOpen.set(false);
    this.selectedUser.set(null);
  }

  saveUser(): void {
    const updated = { ...this.selectedUser(), ...this.editForm() };
    this.users.update(list => list.map(u => u.id === updated.id ? updated : u));
    this.closeEditModal();
  }

  openDeleteModal(user: any): void {
    this.selectedUser.set(user);
    this.deleteModalOpen.set(true);
  }

  closeDeleteModal(): void {
    this.deleteModalOpen.set(false);
    this.selectedUser.set(null);
  }

  deleteUser(): void {
    const id = this.selectedUser()?.id;
    if (id) {
      this.users.update(list => list.filter(u => u.id !== id));
    }
    this.closeDeleteModal();
  }

  loadModels(): void {
    this.loading.set(true);
    this.error.set(null);

    this.ollamaService.getModels().subscribe({
      next: (models) => {
        this.models.set(models);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message || 'Failed to load models. Is Ollama running?');
        this.loading.set(false);
      }
    });
  }

  setActiveModel(name: string): void {
    this.activeModel.set(name);
    localStorage.setItem('activeModel', name);
  }

  pullModel(): void {
    if (!this.pullModelName.trim()) return;

    this.pulling.set(true);
    this.pullError.set(null);
    this.pullSuccess.set(null);

    this.ollamaService.pullModel(this.pullModelName.trim()).subscribe({
      next: (result) => {
        if (result.success) {
          this.pullSuccess.set(result.message || 'Model installed successfully');
          this.pullModelName = '';
          // Auto-refresh models list after successful pull
          this.loadModels();
        } else {
          this.pullError.set(result.error || 'Pull failed');
        }
        this.pulling.set(false);
      },
      error: (err) => {
        this.pullError.set(err?.error?.message || err?.error?.error || 'Failed to pull model');
        this.pulling.set(false);
      }
    });
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'selected': return 'status-active';
      case 'active': return 'status-active';
      case 'installed': return 'status-installed';
      case 'installing': return 'status-installing';
      case 'inactive': return 'status-inactive';
      case 'error': return 'status-error';
      default: return '';
    }
  }
}
