CREATE TABLE `contact_messages` (
	`id` text PRIMARY KEY NOT NULL,
	`created_at` integer NOT NULL,
	`name` text NOT NULL,
	`email` text NOT NULL,
	`project` text NOT NULL,
	`message` text NOT NULL,
	`language` text NOT NULL,
	`status` text DEFAULT 'unread' NOT NULL,
	`ip_hash` text NOT NULL,
	`submission_key` text NOT NULL,
	`payload_hash` text NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `contact_messages_submission_key_unique` ON `contact_messages` (`submission_key`);--> statement-breakpoint
CREATE INDEX `contact_time_idx` ON `contact_messages` (`created_at`);--> statement-breakpoint
CREATE INDEX `contact_ip_idx` ON `contact_messages` (`ip_hash`,`created_at`);