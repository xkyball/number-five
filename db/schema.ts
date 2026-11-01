import { sqliteTable, text, integer, index } from 'drizzle-orm/sqlite-core';
export const messages = sqliteTable('contact_messages', {
  id: text('id').primaryKey(),
  createdAt: integer('created_at').notNull(),
  name: text('name').notNull(), email: text('email').notNull(),
  project: text('project').notNull(), message: text('message').notNull(),
  language: text('language').notNull(), status: text('status').notNull().default('unread'),
  ipHash: text('ip_hash').notNull(), submissionKey: text('submission_key').notNull().unique(),
  payloadHash: text('payload_hash').notNull(),
}, (table) => [index('contact_time_idx').on(table.createdAt), index('contact_ip_idx').on(table.ipHash, table.createdAt)]);
