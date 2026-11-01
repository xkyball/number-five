//! Local state and command protocol for the No5 Spotify Connect receiver.
//!
//! The Spotify protocol is handled by librespot. This crate deliberately
//! exposes no TCP control API: only a mode-restricted Unix socket is used by
//! the co-located No5 gateway.

pub mod control;
pub mod media;
pub mod state;
pub mod volume;
