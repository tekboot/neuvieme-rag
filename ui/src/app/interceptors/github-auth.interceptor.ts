import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

/**
 * HTTP interceptor that handles 401 responses with GITHUB_AUTH_REQUIRED or GITHUB_TOKEN_EXPIRED
 * by redirecting to GitHub OAuth flow.
 */
export const githubAuthInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // Check if this is a GitHub auth issue by looking at the error message
        const errorMessage = error.error?.message || error.message || '';

        if (
          errorMessage.includes('GITHUB_AUTH_REQUIRED') ||
          errorMessage.includes('GITHUB_TOKEN_EXPIRED')
        ) {
          console.warn('[GithubAuthInterceptor] GitHub auth error detected:', errorMessage);
          authService.handleGithubAuthRequired();
          // Return the error but the page will redirect
        }
      }

      // Re-throw the error for other handlers
      return throwError(() => error);
    })
  );
};
