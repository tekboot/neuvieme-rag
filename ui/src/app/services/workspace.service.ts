import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface UploadResult {
  uploaded: number;
}

@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  private base = '/api/workspace';

  constructor(private http: HttpClient) {}

  uploadDeviceFiles(filesWithPath: { path: string; file: File }[]): Observable<UploadResult> {
    const formData = new FormData();

    for (const item of filesWithPath) {
      formData.append('files', item.file);
      formData.append('paths', item.path);
    }

    return this.http.post<UploadResult>(
      `${this.base}/device/upload`,
      formData,
      { withCredentials: true }
    );
  }
}
