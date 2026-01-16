export type NodeType = 'folder' | 'file';
export type ImportSource = 'device' | 'github';

export interface GithubMeta {
  owner: string;
  repo: string;
  branch?: string;
  subPath?: string;
}


export interface FileNode {
  id: string;
  name: string;
  type: NodeType;
  path: string;              // e.g. "src/app/app.component.ts"
  children?: FileNode[];     // only for folders
  expanded?: boolean;        // UI state
  selected?: boolean;        // selection state for context mode
  source?: ImportSource;
  githubMeta?: GithubMeta;
}

export type ChatRole = 'user' | 'assistant' | 'system';

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  ts: number;
}
