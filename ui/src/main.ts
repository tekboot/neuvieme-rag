// src/main.ts
import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app/app.component';
import { githubAuthInterceptor } from './app/interceptors/github-auth.interceptor';
import { routes } from './app/app.routes';
import { ThemeService } from './app/services/theme.service';

// Initialize theme before app bootstrap
const themeService = new ThemeService();
themeService.init();

bootstrapApplication(AppComponent, {
  providers: [
    provideHttpClient(withInterceptors([githubAuthInterceptor])),
    provideRouter(routes)
  ]
});
