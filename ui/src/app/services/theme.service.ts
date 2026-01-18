import { Injectable, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

/**
 * Service to manage application theme (dark/light mode).
 * Persists theme choice in localStorage and applies data-theme attribute to document root.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
    private readonly STORAGE_KEY = 'theme';

    // Expose theme as a signal so components can bind to it
    theme = signal<Theme>('dark');

    /**
     * Initialize theme from localStorage and apply to document root.
     * Should be called early in app bootstrap (main.ts).
     */
    init(): void {
        const saved = localStorage.getItem(this.STORAGE_KEY) as Theme | null;
        const initialTheme = saved || 'light';
        this.theme.set(initialTheme);
        this.applyTheme(initialTheme);
    }

    /**
     * Toggle between dark and light themes
     */
    toggle(): void {
        const newTheme = this.theme() === 'dark' ? 'light' : 'dark';
        this.setTheme(newTheme);
    }

    /**
     * Set a specific theme
     */
    setTheme(theme: Theme): void {
        this.theme.set(theme);
        localStorage.setItem(this.STORAGE_KEY, theme);
        this.applyTheme(theme);
    }

    /**
     * Apply theme by setting data-theme attribute on document root
     */
    private applyTheme(theme: Theme): void {
        document.documentElement.setAttribute('data-theme', theme);
    }
}
