package io.ib67.prts.agent.acp.entity;

/** Which end of the proxy a recorded frame came from. */
public enum AgentDirection {
    /** Sent by the job's agent, through its worker. */
    FROM_AGENT,
    /** Sent by an attached viewer. */
    FROM_CLIENT
}
