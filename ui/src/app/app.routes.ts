import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: 'admin',
    loadComponent: () => import('./pages/admin/admin.component').then(m => m.AdminComponent)
  },
  {
    path: 'super-admin',
    loadComponent: () => import('./pages/super-admin/super-admin.component').then(m => m.SuperAdminComponent)
  }
];
